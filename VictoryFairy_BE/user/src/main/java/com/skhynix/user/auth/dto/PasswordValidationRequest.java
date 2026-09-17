package com.skhynix.user.auth.dto;

// 검증 애노테이션을 붙이지 말 것 — 임의 문자열을 200으로 판정해 돌려주는 계약이라, 붙이면 400이 나간다.
public record PasswordValidationRequest(
        String password
) {
}
