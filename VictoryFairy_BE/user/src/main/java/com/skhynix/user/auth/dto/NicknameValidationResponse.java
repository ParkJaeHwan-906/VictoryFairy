package com.skhynix.user.auth.dto;

import com.skhynix.user.auth.policy.NicknamePolicy;

public record NicknameValidationResponse(
        boolean valid,
        String message
) {

    /** 이름이 {@code valid()}이면 record 접근자와 충돌하므로 {@code passed()}로 둔다. */
    public static NicknameValidationResponse passed() {
        return new NicknameValidationResponse(true, NicknamePolicy.VALID_MESSAGE);
    }

    public static NicknameValidationResponse violated(String violationMessage) {
        return new NicknameValidationResponse(false, violationMessage);
    }
}
