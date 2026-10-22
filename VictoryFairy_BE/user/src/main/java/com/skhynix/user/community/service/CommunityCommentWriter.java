package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentImage;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.dto.CommentItemResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글·답글 쓰기의 <b>트랜잭션 단위</b>. 구조와 {@code prepare*} 의 존재 이유는 {@link CommunityPostWriter} 와
 * 같다. 답글 전용 메서드는 없다 — 생성 시 {@code parentCommentId} 유무로만 갈린다.
 *
 * <p>글의 {@code commentCount} 는 "보이는 댓글 + 보이는 답글"이다. 작성 +1, 보이던 행의 삭제 −1 — 부모를
 * 지워도 답글 수는 그대로다(답글 행은 손대지 않으니 당연히 그렇다).
 */
@Service
@RequiredArgsConstructor
public class CommunityCommentWriter {

    public record UpdateResult(CommentItemResponse item, List<String> removedEndpoints) {
    }

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityCommentImageRepository commentImageRepository;
    private final UserAccountRepository userAccountRepository;
    private final CommentResponseAssembler assembler;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void prepareCreate(Long postId, Long parentCommentId) {
        CommunityPost post = requireWritablePost(postId);
        if (parentCommentId != null) {
            requireParent(post, parentCommentId);
        }
    }

    @Transactional(readOnly = true)
    public List<String> prepareUpdate(Long userAccountId, Long commentId) {
        return endpointsOf(requireEditable(userAccountId, commentId).getId());
    }

    @Transactional
    public Long create(Long userAccountId, Long postId, Long parentCommentId, String content,
            List<String> endpoints) {
        CommunityPost post = requireWritablePost(postId);
        CommunityComment parent = parentCommentId == null ? null : requireParent(post, parentCommentId);
        UserAccount author = requireAccount(userAccountId);

        // 커뮤니티의 모든 시각은 이 Clock 하나에서 나온다(CommunityPostWriter.create 와 같은 이유)
        LocalDateTime now = LocalDateTime.now(clock);
        CommunityComment comment = commentRepository.save(CommunityComment.builder()
                .post(post)
                .parent(parent)
                .userAccount(author)
                .content(content)
                .createdAt(now)
                .build());
        saveImages(comment, endpoints, now);
        postRepository.adjustCommentCount(postId, 1);
        return comment.getId();
    }

    @Transactional
    public UpdateResult update(Long userAccountId, Long commentId, String content, List<String> endpoints) {
        CommunityComment comment = requireEditable(userAccountId, commentId);

        List<String> removed = new ArrayList<>(endpointsOf(commentId));
        removed.removeAll(endpoints);

        // updatedAt 의 유일한 출처는 이 Clock 값이다(CommunityPostWriter.update 와 같은 이유)
        LocalDateTime now = LocalDateTime.now(clock);
        comment.edit(content, now);
        commentImageRepository.deleteAllByCommentId(commentId);
        saveImages(comment, endpoints, now);
        // 응답을 만들기 전에 DB 에 반영(CommunityPostWriter.update 와 같은 이유)
        commentRepository.flush();

        CommentItemResponse item = comment.isReply()
                ? assembler.assembleReply(comment, userAccountId)
                : assembler.assemble(List.of(comment), userAccountId).get(0);
        return new UpdateResult(item, removed);
    }

    /**
     * 그 행만 소프트 삭제한다 — 답글에 전파하지 않는다. 블라인드된 행도 작성자는 지울 수 있고, 소속 글이
     * 블라인드여도 막지 않는다(410 예외 둘 중 하나). 재삭제는 멱등 204 가 아니라 404 다.
     *
     * <p>행을 <b>잠그고</b> 읽는다 — 신고({@code CommunityReportService.reportComment})와 같은 락 쿼리다. 둘이 동시에
     * 들어오면 락 없이는 양쪽 다 "보이는 행"을 보고 {@code commentCount} 를 두 번 뺀다. 잠그면 뒤에 온 쪽이
     * 앞쪽의 커밋 결과(블라인드됨 또는 삭제됨)를 보고 각각 "다시 안 뺌"·404 로 끝난다.
     *
     * @return 이 댓글에 붙은 확정 EP
     */
    @Transactional
    public List<String> delete(Long userAccountId, Long commentId) {
        CommunityComment comment = requireExisting(
                commentRepository.findWithLockByIdAndDeletedAtIsNull(commentId));
        requireAuthor(comment, userAccountId);

        boolean wasVisible = comment.isVisible();
        comment.delete(LocalDateTime.now(clock));
        if (wasVisible) {
            // 블라인드 상태였다면 신고 시점에 이미 빠졌다 — 두 번 빼지 않는다
            postRepository.adjustCommentCount(comment.getPost().getId(), -1);
        }
        return endpointsOf(commentId);
    }

    // 글 404 → 글 410
    private CommunityPost requireWritablePost(Long postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        if (post.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        return post;
    }

    // 부모 404(없음·삭제·다른 글) → 400(답글의 답글) → 410(블라인드 부모에는 새 답글 불가)
    private CommunityComment requireParent(CommunityPost post, Long parentCommentId) {
        CommunityComment parent = commentRepository.findByIdAndDeletedAtIsNull(parentCommentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        if (!parent.getPost().getId().equals(post.getId())) {
            // 글 스코프 밖은 "그 글에 없는 댓글"이다 — 채팅의 room-스코프 조회와 같은 판단
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        if (parent.isReply()) {
            throw new BusinessException(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED);
        }
        if (parent.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED);
        }
        return parent;
    }

    // 댓글·글 404 → 작성자 403 → 글 410 → 댓글 410
    private CommunityComment requireEditable(Long userAccountId, Long commentId) {
        CommunityComment comment = requireExisting(commentId);
        requireAuthor(comment, userAccountId);
        if (comment.getPost().isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        if (comment.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED);
        }
        return comment;
    }

    // 어느 쪽이 삭제됐는지 구분하지 않는다 — 댓글이든 소속 글이든 같은 404
    private CommunityComment requireExisting(Long commentId) {
        return requireExisting(commentRepository.findByIdAndDeletedAtIsNull(commentId));
    }

    private CommunityComment requireExisting(Optional<CommunityComment> found) {
        CommunityComment comment = found
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        if (comment.getPost().isDeleted()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        return comment;
    }

    private void requireAuthor(CommunityComment comment, Long userAccountId) {
        if (!comment.getUserAccount().getId().equals(userAccountId)) {
            throw new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR);
        }
    }

    private UserAccount requireAccount(Long userAccountId) {
        return userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private List<String> endpointsOf(Long commentId) {
        return commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(commentId).stream()
                .map(CommunityCommentImage::getEndpoint)
                .toList();
    }

    private void saveImages(CommunityComment comment, List<String> endpoints, LocalDateTime now) {
        List<CommunityCommentImage> images = new ArrayList<>(endpoints.size());
        for (int i = 0; i < endpoints.size(); i++) {
            images.add(CommunityCommentImage.builder()
                    .comment(comment)
                    .endpoint(endpoints.get(i))
                    .sortOrder(i)
                    .createdAt(now)
                    .build());
        }
        commentImageRepository.saveAll(images);
    }
}
