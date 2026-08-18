package com.skhynix.user.account.dto;

import com.skhynix.user.auth.policy.ValidPassword;

public record PasswordUpdateRequest(

        // 검증 애노테이션을 걸지 않는다 — 누락·null 은 400 "현재 비밀번호가 올바르지 않습니다."로
        // 흡수한다(서비스 판정). @NotBlank 를 걸면 형식 위반과 함께 왔을 때 위반이 2개가 된다.
        String currentPassword,

        // PasswordPolicy 가 단일 출처 — 겹쳐 걸지 말 것(ValidPassword 참고).
        @ValidPassword
        String newPassword
) {
}
