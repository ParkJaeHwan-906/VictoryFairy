package com.skhynix.user.auth.dto;

/**
 * 비밀번호 사전 검사 요청.
 *
 * <p><b>의도적으로 검증 애노테이션을 붙이지 않는다.</b> 이 API는 임의의 문자열을 받아 판정 결과를
 * 200으로 돌려주는 것이 계약이므로, {@code @Size}/{@code @Pattern}을 붙이면 {@code @Valid}가
 * 400을 던져 계약이 깨진다. 정책 판정은 {@code PasswordPolicy}가 담당한다.
 */
public record PasswordValidationRequest(
        String password
) {
}
