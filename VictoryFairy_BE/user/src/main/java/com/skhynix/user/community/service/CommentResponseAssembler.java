package com.skhynix.user.community.service;

import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentImage;
import com.skhynix.domain.community.entity.CommunityCommentReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityCommentReactionRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.ReplyResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 최상위 댓글 N 건을 응답 항목으로 조립한다 — 목록 한 페이지와 수정 응답이 같은 조립을 쓴다.
 *
 * <p>쿼리 3개로 닫는다: 보이는 답글(IN) + 이미지(IN) + 내 반응(IN). 항목마다 부르면 N+1 이다.
 * 자리 표식(삭제·블라인드 부모)은 본문·이미지·반응을 읽지 않으므로 그 id 는 뒤 두 쿼리에서 뺀다.
 * ⚠ 트랜잭션 안에서 불러야 한다 — 작성자(LAZY)는 fetch 돼 있지만 {@code parent.getId()} 외의 프록시
 * 접근이 생기면 밖에서는 깨진다.
 */
@Component
@RequiredArgsConstructor
public class CommentResponseAssembler {

    private final CommunityCommentRepository commentRepository;
    private final CommunityCommentImageRepository commentImageRepository;
    private final CommunityCommentReactionRepository commentReactionRepository;

    public List<CommentResponse> assemble(List<CommunityComment> parents, Long requesterId) {
        if (parents.isEmpty()) {
            return List.of();
        }
        List<Long> parentIds = parents.stream().map(CommunityComment::getId).toList();
        List<CommunityComment> replies = commentRepository.findVisibleRepliesByParentIds(parentIds);

        List<Long> visibleIds = new ArrayList<>();
        parents.stream().filter(CommunityComment::isVisible).map(CommunityComment::getId)
                .forEach(visibleIds::add);
        replies.stream().map(CommunityComment::getId).forEach(visibleIds::add);

        Map<Long, List<String>> images = imagesOf(visibleIds);
        Map<Long, ReactionType> reactions = reactionsOf(requesterId, visibleIds);

        // 답글은 id 오름차순으로 왔으므로 부모별로 모아도 작성순이 유지된다
        Map<Long, List<ReplyResponse>> repliesByParent = new LinkedHashMap<>();
        for (CommunityComment reply : replies) {
            repliesByParent.computeIfAbsent(reply.getParent().getId(), k -> new ArrayList<>())
                    .add(ReplyResponse.of(reply, images.getOrDefault(reply.getId(), List.of()),
                            reactions.get(reply.getId()), requesterId));
        }

        List<CommentResponse> result = new ArrayList<>(parents.size());
        for (CommunityComment parent : parents) {
            result.add(CommentResponse.of(parent,
                    images.getOrDefault(parent.getId(), List.of()),
                    reactions.get(parent.getId()),
                    requesterId,
                    repliesByParent.getOrDefault(parent.getId(), List.of())));
        }
        return result;
    }

    /** 답글 한 건의 응답 — 수정 응답이 쓴다. 쿼리 2개(이미지·내 반응). */
    public ReplyResponse assembleReply(CommunityComment reply, Long requesterId) {
        List<Long> ids = List.of(reply.getId());
        return ReplyResponse.of(reply, imagesOf(ids).getOrDefault(reply.getId(), List.of()),
                reactionsOf(requesterId, ids).get(reply.getId()), requesterId);
    }

    private Map<Long, List<String>> imagesOf(List<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> images = new HashMap<>();
        for (CommunityCommentImage image : commentImageRepository
                .findAllByComment_IdInOrderBySortOrderAsc(commentIds)) {
            images.computeIfAbsent(image.getComment().getId(), k -> new ArrayList<>())
                    .add(image.getEndpoint());
        }
        return images;
    }

    private Map<Long, ReactionType> reactionsOf(Long requesterId, List<Long> commentIds) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ReactionType> reactions = new HashMap<>();
        for (CommunityCommentReaction reaction : commentReactionRepository
                .findAllByUserAccount_IdAndComment_IdIn(requesterId, commentIds)) {
            reactions.put(reaction.getComment().getId(), reaction.getType());
        }
        return reactions;
    }
}
