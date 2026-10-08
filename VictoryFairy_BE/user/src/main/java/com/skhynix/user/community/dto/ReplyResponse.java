package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.ReactionType;
import java.time.LocalDateTime;
import java.util.List;

/** 답글 항목(키 12개, {@code replies} 없음). 보이는 답글만 목록에 실리므로 {@code status} 는 항상 VISIBLE. */
public record ReplyResponse(
        Long commentId,
        Long parentCommentId,
        CommentStatus status,
        String content,
        List<String> imageUrls,
        AuthorResponse author,
        long likeCount,
        long dislikeCount,
        ReactionType myReaction,
        boolean isAuthor,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) implements CommentItemResponse {

    public static ReplyResponse of(CommunityComment reply, List<String> imageUrls,
            ReactionType myReaction, Long requesterId) {
        return new ReplyResponse(
                reply.getId(),
                reply.getParent().getId(),
                CommentStatus.VISIBLE,
                reply.getContent(),
                imageUrls,
                AuthorResponse.from(reply.getUserAccount()),
                reply.getLikeCount(),
                reply.getDislikeCount(),
                myReaction,
                reply.getUserAccount().getId().equals(requesterId),
                reply.getCreatedAt(),
                reply.getUpdatedAt());
    }
}
