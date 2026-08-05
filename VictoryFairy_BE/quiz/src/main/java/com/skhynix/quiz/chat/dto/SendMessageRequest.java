package com.skhynix.quiz.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 메시지 전송 요청. {@code @Size(max = 500)}는 {@code String.length()}(UTF-16 code unit) 기준이라 이모지
 * surrogate pair는 2로 계수된다. {@code @Valid}는 컨트롤러 진입 전에 검증되므로 content 위반 400이 방
 * 미존재 404보다 먼저 판정된다.
 */
public record SendMessageRequest(

        @NotBlank
        @Size(max = 500)
        String content
) {
}
