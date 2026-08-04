package com.skhynix.user.support.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 응원 선수 추가·취소 요청. 두 엔드포인트가 같은 본문 형태를 공유한다.
 *
 * <p>필드 부재는 400, 빈 배열은 허용(변경 없음)이라 {@code @NotEmpty} 가 아니라 {@code @NotNull} 이다.
 * 중복 id 는 검증에서 막지 않고 서비스가 제거한다.
 *
 * @param playerIds 대상 선수 PK 목록({@code GET /api/member/players} 의 {@code data[].id})
 */
public record SupportPlayersRequest(
        @NotNull(message = "선수 목록이 필요합니다.") List<Long> playerIds) {
}
