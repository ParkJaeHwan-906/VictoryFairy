package com.skhynix.user.support.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 응원 선수 추가·취소 요청. 두 엔드포인트가 같은 본문 형태를 공유한다.
 *
 * <p><b>빈 배열은 허용하고 {@code null} 만 막는다</b>(USER-SP-21) — 선수 응원은 필수가 아니므로 "아무것도
 * 지정하지 않은 요청"은 오류가 아니라 아무 변경이 없는 요청이다. 반면 필드 자체가 없는 요청은 클라이언트
 * 버그라 400 으로 되돌린다. 그래서 {@code @NotEmpty} 가 아니라 {@code @NotNull} 이다.
 *
 * <p>중복 id 는 검증으로 막지 않고 서비스가 제거한다(USER-SP-20) — 중복은 사용자 실수가 아니라 화면에서
 * 흔히 생기는 잡음이고, 400 을 주면 클라이언트가 정제 책임을 떠안아야 한다.
 *
 * @param playerIds 대상 선수 PK 목록({@code GET /api/member/players} 의 {@code data[].id})
 */
public record SupportPlayersRequest(
        @NotNull(message = "선수 목록이 필요합니다.") List<Long> playerIds) {
}
