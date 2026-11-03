package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.ReactionType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 상세. 댓글 목록은 싣지 않는다({@code commentCount} 만) — 댓글은 별도 페이징 경로다.
 * {@code viewCount} 는 호출자가 이번 요청의 증가분을 반영해 넘긴다.
 */
public record PostDetailResponse(
        Long postId,
        Long categoryId,
        String categoryName,
        String title,
        String content,
        List<String> imageUrls,
        AuthorResponse author,
        long viewCount,
        long likeCount,
        long dislikeCount,
        long commentCount,
        ReactionType myReaction,
        boolean isAuthor,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PostDetailResponse of(CommunityPost post, List<String> imageUrls, long viewCount,
            ReactionType myReaction, boolean isAuthor) {
        return new PostDetailResponse(
                post.getId(),
                post.getCategory().getId(),
                post.getCategory().getName(),
                post.getTitle(),
                post.getContent(),
                imageUrls,
                AuthorResponse.from(post.getUserAccount()),
                viewCount,
                post.getLikeCount(),
                post.getDislikeCount(),
                post.getCommentCount(),
                myReaction,
                isAuthor,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
