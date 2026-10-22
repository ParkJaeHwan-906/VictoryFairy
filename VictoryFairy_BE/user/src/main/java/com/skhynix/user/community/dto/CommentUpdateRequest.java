package com.skhynix.user.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 댓글·답글 수정 본문(전체 교체). 부모는 바꿀 수 없어 {@code parentCommentId} 가 없다. */
public record CommentUpdateRequest(

        @NotBlank
        @Size(max = 1000)
        String content,

        List<String> imageUrls
) {

    public List<String> imageUrlsOrEmpty() {
        return imageUrls == null ? List.of() : imageUrls;
    }
}
