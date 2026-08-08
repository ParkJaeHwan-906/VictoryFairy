package com.skhynix.user.support.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 응원 구단 선택·변경 요청. 대상 계정은 access 토큰에서만 정해져 {@code userId} 필드를 두지 않는다
 * (타인의 응원을 조작할 입력 경로를 만들지 않는다).
 *
 * @param teamId 응원할 구단 PK({@code GET /api/teams} 의 {@code data[].id})
 */
public record SupportTeamRequest(@NotNull(message = "응원할 구단을 선택해 주세요.") Long teamId) {
}
