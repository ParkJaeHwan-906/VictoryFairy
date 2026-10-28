package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.ReactionType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 최상위 댓글 항목 = {@link ReplyResponse} 의 키 + {@code replies}. {@code parentCommentId} 는 항상 {@code null}.
 *
 * <p>자리 표식({@code status} 가 DELETED·BLINDED)은 본문·이미지·작성자·카운트·내 반응·수정 시각을 전부
 * 비운다 — 작성자 본인이 봐도 같다. 살아남는 것은 {@code commentId}·{@code createdAt}·{@code replies} 뿐이다.
 * 행의 like_count 는 보존되지만 응답에는 0 으로 고정한다(표현이지 저장값이 아니다).
 */
public record CommentResponse(
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
        LocalDateTime updatedAt,
        List<ReplyResponse> replies) implements CommentItemResponse {

    public static CommentResponse of(CommunityComment comment, List<String> imageUrls,
            ReactionType myReaction, Long requesterId, List<ReplyResponse> replies) {
        CommentStatus status = CommentStatus.of(comment);
        if (status != CommentStatus.VISIBLE) {
            return placeholder(comment, status, replies);
        }
        return new CommentResponse(
                comment.getId(),
                null,
                status,
                comment.getContent(),
                imageUrls,
                AuthorResponse.from(comment.getUserAccount()),
                comment.getLikeCount(),
                comment.getDislikeCount(),
                myReaction,
                comment.getUserAccount().getId().equals(requesterId),
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                replies);
    }

    private static CommentResponse placeholder(CommunityComment comment, CommentStatus status,
            List<ReplyResponse> replies) {
        return new CommentResponse(
                comment.getId(),
                null,
                status,
                null,
                List.of(),
                null,
                0L,
                0L,
                null,
                false,
                comment.getCreatedAt(),
                null,
                replies);
    }
}
