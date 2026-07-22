package com.skhynix.user.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 인증번호 검증 요청. email/code의 형식(@NotBlank·@Email·6자리 숫자)은 Bean Validation(400)이 막고,
 * 저장값과의 대조(불일치/만료/시도초과)는 형식 통과 후 서비스가 BusinessException으로 판정한다.
 */
public record EmailVerifyRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자여야 합니다.")
        String code
) {
}
