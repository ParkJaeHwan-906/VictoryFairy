package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityPost;
import java.time.LocalDateTime;

/**
 * 내 글 항목 = {@link PostSummaryResponse} 의 11개 키 + {@code blinded}. 상속이 안 되는 record 라 필드를
 * 다시 적는다 — 두 record 의 키가 어긋나면 "PostSummary + blinded" 계약이 깨지니 함께 고칠 것.
 */
public record MyPostSummaryResponse(
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
        LocalDateTime createdAt,
        boolean blinded) {

    public static MyPostSummaryResponse of(CommunityPost post, String thumbnailUrl) {
        return new MyPostSummaryResponse(
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
                post.getCreatedAt(),
                post.isBlinded());
    }
}
