package com.skhynix.user.auth.dto;

import com.skhynix.user.auth.policy.PasswordPolicy;

/**
 * 비밀번호 사전 검사 결과.
 *
 * @param valid   정책 만족 여부
 * @param message 위반 시 위반한 규칙 메시지 1개, 통과 시 안내 메시지
 */
public record PasswordValidationResponse(
        boolean valid,
        String message
) {

    /** 이름이 {@code valid()}이면 record 접근자와 충돌하므로 {@code passed()}로 둔다. */
    public static PasswordValidationResponse passed() {
        return new PasswordValidationResponse(true, PasswordPolicy.VALID_MESSAGE);
    }

    public static PasswordValidationResponse violated(String violationMessage) {
        return new PasswordValidationResponse(false, violationMessage);
    }
}
