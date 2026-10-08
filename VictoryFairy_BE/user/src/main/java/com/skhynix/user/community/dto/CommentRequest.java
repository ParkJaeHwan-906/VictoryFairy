package com.skhynix.user.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 댓글·답글 작성 본문. {@code parentCommentId} 가 있으면 답글이다 — 답글 전용 경로는 없다.
 * {@code imageUrls} 에 검증 애노테이션을 걸지 않는 이유는 {@link PostRequest} 와 같다.
 */
public record CommentRequest(

        @NotBlank
        @Size(max = 1000)
        String content,

        List<String> imageUrls,

        Long parentCommentId
) {

    public List<String> imageUrlsOrEmpty() {
        return imageUrls == null ? List.of() : imageUrls;
    }
}
