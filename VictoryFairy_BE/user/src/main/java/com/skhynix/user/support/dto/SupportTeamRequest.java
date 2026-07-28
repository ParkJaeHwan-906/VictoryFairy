package com.skhynix.user.support.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 응원 구단 선택·변경 요청.
 *
 * <p>대상 계정은 본문이 아니라 access 토큰에서만 정해지므로 {@code userId}/{@code uid} 필드를 두지 않는다
 * (타인의 응원을 조작할 입력 경로를 만들지 않는다 — USER-SP-2).
 *
 * <p>{@code teamId} 가 필수인 것이 "응원 구단은 필수"의 강제 지점이다(USER-SP-4). 구단 응원을 해제하는
 * 경로는 없고 변경만 있으므로 이 값이 비는 경우는 계약상 존재하지 않는다.
 *
 * @param teamId 응원할 구단 PK({@code GET /api/member/teams} 의 {@code data[].id})
 */
public record SupportTeamRequest(@NotNull(message = "응원할 구단을 선택해 주세요.") Long teamId) {
}
