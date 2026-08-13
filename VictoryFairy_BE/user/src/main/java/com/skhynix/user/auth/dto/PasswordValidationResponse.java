package com.skhynix.user.auth.dto;

import com.skhynix.user.auth.policy.PasswordPolicy;

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
