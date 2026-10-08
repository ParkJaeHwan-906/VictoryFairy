package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentReaction;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityCommentReactionRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.dto.ReactionChoice;
import com.skhynix.user.community.dto.ReactionResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3상태 반응(LIKE/DISLIKE/없음)의 상태 지정. 토글이 아니라 <b>요청값이 곧 결과 상태</b>라 같은 값을 몇 번
 * 보내도 카운트가 안 변한다 — "두 번 누를 수 없다"의 구현이 거절(4xx)이 아니라 멱등 응답이다.
 *
 * <p>대상 행을 <b>트랜잭션 맨 앞에서 잠근다.</b> 카운터는 엔티티 값에서 더하므로 잠금 없이는 서로 다른 계정의
 * 동시 반응에서 한쪽 갱신이 유실되고, 같은 계정의 동시 요청 2건은 둘 다 "행 없음"을 보고 INSERT 를 시도한다
 * (UNIQUE 가 심판하지만 그때 한쪽은 500 이다). 잠그면 두 번째가 첫 번째의 행을 보고 no-op 으로 끝난다.
 * 자기 글·자기 댓글 반응은 막지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityReactionService {

    private final CommunityPostRepository postRepository;
    private final CommunityPostReactionRepository postReactionRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityCommentReactionRepository commentReactionRepository;
    private final UserAccountRepository userAccountRepository;
    // 반응 행의 created_at/updated_at 출처 — 커뮤니티 엔티티는 Hibernate 생성값(컨테이너 UTC)을 쓰지 않는다
    private final Clock clock;

    public ReactionResponse reactToPost(Long userAccountId, Long postId, ReactionChoice choice) {
        CommunityPost post = postRepository.findWithLockByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        if (post.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }

        Optional<CommunityPostReaction> existing =
                postReactionRepository.findByUserAccount_IdAndPost_Id(userAccountId, postId);
        ReactionType desired = choice.type();
        LocalDateTime now = LocalDateTime.now(clock);

        if (desired == null) {
            existing.ifPresent(reaction -> {
                post.adjustReaction(reaction.getType(), -1);
                postReactionRepository.delete(reaction);
            });
        } else if (existing.isEmpty()) {
            postReactionRepository.save(CommunityPostReaction.builder()
                    .userAccount(requireAccount(userAccountId))
                    .post(post)
                    .type(desired)
                    .createdAt(now)
                    .build());
            post.adjustReaction(desired, 1);
        } else if (existing.get().getType() != desired) {
            // 행은 하나이고 type 만 바뀐다 — 둘이 동시에 켜진 순간이 없다
            post.adjustReaction(existing.get().getType(), -1);
            existing.get().change(desired, now);
            post.adjustReaction(desired, 1);
        }
        return new ReactionResponse(desired, post.getLikeCount(), post.getDislikeCount());
    }

    // 댓글·답글 공용. 댓글 404 → 글 404 → 글 410 → 댓글 410
    public ReactionResponse reactToComment(Long userAccountId, Long commentId, ReactionChoice choice) {
        CommunityComment comment = commentRepository.findWithLockByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        if (comment.getPost().isDeleted()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        if (comment.getPost().isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        if (comment.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED);
        }

        Optional<CommunityCommentReaction> existing =
                commentReactionRepository.findByUserAccount_IdAndComment_Id(userAccountId, commentId);
        ReactionType desired = choice.type();
        LocalDateTime now = LocalDateTime.now(clock);

        if (desired == null) {
            existing.ifPresent(reaction -> {
                comment.adjustReaction(reaction.getType(), -1);
                commentReactionRepository.delete(reaction);
            });
        } else if (existing.isEmpty()) {
            commentReactionRepository.save(CommunityCommentReaction.builder()
                    .userAccount(requireAccount(userAccountId))
                    .comment(comment)
                    .type(desired)
                    .createdAt(now)
                    .build());
            comment.adjustReaction(desired, 1);
        } else if (existing.get().getType() != desired) {
            comment.adjustReaction(existing.get().getType(), -1);
            existing.get().change(desired, now);
            comment.adjustReaction(desired, 1);
        }
        return new ReactionResponse(desired, comment.getLikeCount(), comment.getDislikeCount());
    }

    private UserAccount requireAccount(Long userAccountId) {
        return userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
