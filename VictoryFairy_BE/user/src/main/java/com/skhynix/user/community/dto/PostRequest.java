package com.skhynix.user.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 작성·수정이 같은 본문을 쓴다(수정은 PUT 전체 교체라 필드 집합이 작성과 같다).
 *
 * <p>{@code @NotBlank} 와 {@code @Size} 를 겹쳐 걸어도 비결정 문제가 없다 — 빈 값과 초과는 같은 값이 둘 다
 * 위반할 수 없는 조건이다. {@code imageUrls} 에는 검증 애노테이션을 걸지 않는다: 개수·모양·존재를 한 주체
 * (CommunityImageAttacher)가 판정해 코드 형식의 400 으로 응답한다(SignupRequest.profileImgUrl 과 같은 이유).
 */
public record PostRequest(

        @NotNull
        Long categoryId,

        @NotBlank
        @Size(max = 100)
        String title,

        @NotBlank
        @Size(max = 5000)
        String content,

        // 생략·null 은 빈 배열과 같다
        List<String> imageUrls
) {

    public List<String> imageUrlsOrEmpty() {
        return imageUrls == null ? List.of() : imageUrls;
    }
}
