package com.skhynix.quiz.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 메시지 전송 요청.
 *
 * <p>{@code @NotBlank}가 null·빈 문자열·공백만(trim 후 빈 값)을 400으로 막고, {@code @Size(max = 500)}가
 * 상한을 건다. 길이는 {@code String.length()}(UTF-16 code unit) 기준이라 이모지 surrogate pair는 2로
 * 계수된다(닉네임 정책과 동일 컨벤션). {@code @Valid}가 컨트롤러 진입 전(인자 바인딩 단계)에 검증하므로
 * content 위반 400이 방 미존재 404보다 먼저 판정된다.
 */
public record SendMessageRequest(

        @NotBlank
        @Size(max = 500)
        String content
) {
}
