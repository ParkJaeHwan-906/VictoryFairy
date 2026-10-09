package com.skhynix.user.game.realtime;

import com.skhynix.user.game.dto.GameResponse;
import java.time.LocalDate;
import java.util.List;

/**
 * 경기 SSE 구독자에게 나가는 {@code game-update} 이벤트의 본문이자, 파드 간 팬아웃(Redis)에 실리는 메시지.
 *
 * <p>{@code game}은 {@code GET /api/games} 항목과 **같은 13필드**다 — 클라이언트가 목록의 해당 항목을
 * 이 값으로 통째로 바꿔 끼우면 되게 하기 위해서다(델타 머지 규칙을 따로 두지 않는다).
 * {@code changed}는 수집기가 직전 폴링과 비교해 달라졌다고 본 필드 이름 목록
 * ({@code inning}·{@code homeScore}·{@code awayScore}·{@code status})이고, {@code observedAt}은 수집기의
 * 관측 시각(UTC ISO-8601)이다 — 둘 다 표시용 힌트일 뿐 클라이언트 상태의 근거는 {@code game}이다.
 *
 * @param game       갱신된 경기(13필드)
 * @param changed    달라진 필드 이름 목록(수집기 판정, 비어 있지 않다)
 * @param observedAt 수집기 관측 시각(UTC ISO-8601 문자열)
 */
public record GameUpdateEvent(GameResponse game, List<String> changed, String observedAt) {

    /** 구독 키 계산에 쓰는 경기 날짜 — {@code gameDate}의 날짜 부분(KST 벽시계). */
    public LocalDate date() {
        return game.gameDate().toLocalDate();
    }
}
