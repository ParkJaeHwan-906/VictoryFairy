package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityPost;
import java.time.LocalDateTime;

/** 목록·인기 항목. 본문은 싣지 않는다. {@code thumbnailUrl} 은 첫 이미지 EP 또는 {@code null}. */
public record PostSummaryResponse(
        Long postId,
        Long categoryId,
        String categoryName,
        String title,
        String thumbnailUrl,
        AuthorResponse author,
        long viewCount,
        long likeCount,
        long dislikeCount,
        long commentCount,
        LocalDateTime createdAt) {

    public static PostSummaryResponse of(CommunityPost post, String thumbnailUrl) {
        return new PostSummaryResponse(
                post.getId(),
                post.getCategory().getId(),
                post.getCategory().getName(),
                post.getTitle(),
                thumbnailUrl,
                AuthorResponse.from(post.getUserAccount()),
                post.getViewCount(),
                post.getLikeCount(),
                post.getDislikeCount(),
                post.getCommentCount(),
                post.getCreatedAt());
    }
}
