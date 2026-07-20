package com.skhynix.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 인증번호 발송 요청. 이메일 형식 검증(400)은 여기서 끝나고, 가입 이력·발송 정책 판정은 서비스가 한다.
 */
public record EmailSendCodeRequest(

        @NotBlank
        @Email
        @Size(max = 100)
        String email
) {
}
