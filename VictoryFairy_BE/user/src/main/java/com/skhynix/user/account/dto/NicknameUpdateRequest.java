package com.skhynix.user.account.dto;

import com.skhynix.user.auth.policy.ValidNickname;

public record NicknameUpdateRequest(

        // NicknamePolicy 가 단일 출처 — @NotBlank·@Size·@Pattern 을 겹쳐 걸지 말 것(ValidNickname 참고).
        // 겹치면 동시 위반 시 응답 메시지가 비결정적이 돼 "위반은 항상 정확히 1개" 계약이 깨진다.
        @ValidNickname
        String nickname
) {
}
