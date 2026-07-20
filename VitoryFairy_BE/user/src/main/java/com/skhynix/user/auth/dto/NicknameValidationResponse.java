package com.skhynix.user.auth.dto;

import com.skhynix.user.auth.policy.NicknamePolicy;

/**
 * 닉네임 사전 검사 결과. {@link PasswordValidationResponse}를 미러링한다.
 *
 * <p>실패 종류(길이·문자 구성·중복)는 별도 코드 필드 없이 {@code message} 문구로만 구분한다.
 * 중복 역시 {@code valid:false} 케이스이므로 {@link #violated(String)} 팩토리를 그대로 재사용한다.
 *
 * @param valid   정책·중복 검사를 모두 통과했는지 여부
 * @param message 위반 시 위반한 규칙 메시지 1개, 통과 시 안내 메시지
 */
public record NicknameValidationResponse(
        boolean valid,
        String message
) {

    /** 이름이 {@code valid()}이면 record 접근자와 충돌하므로 {@code passed()}로 둔다. */
    public static NicknameValidationResponse passed() {
        return new NicknameValidationResponse(true, NicknamePolicy.VALID_MESSAGE);
    }

    /** 정책 위반·중복 어느 쪽이든 실패 결과는 이 팩토리로 만든다. */
    public static NicknameValidationResponse violated(String violationMessage) {
        return new NicknameValidationResponse(false, violationMessage);
    }
}
