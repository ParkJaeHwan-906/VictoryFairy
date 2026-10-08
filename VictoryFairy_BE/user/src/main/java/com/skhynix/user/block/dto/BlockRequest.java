package com.skhynix.user.block.dto;

import jakarta.validation.constraints.NotBlank;

// 차단 대상은 닉네임으로만 식별한다(USER-BLK-1). blockerId/blockerUid 류 필드를 두지 않는다 — 차단
// 주체는 access 토큰 principal 로만 정해진다(USER-BLK-3), 본문·경로로 대신 차단시킬 입력 경로가 없다.
public record BlockRequest(
        @NotBlank(message = "차단할 사용자의 닉네임을 입력해 주세요.")
        String targetNickname
) {
}
