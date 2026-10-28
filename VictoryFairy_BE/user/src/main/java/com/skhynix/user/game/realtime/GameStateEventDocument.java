package com.skhynix.user.game.realtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * py-collector(AI 레포)가 S3 {@code game-state-events/{date}/{gameId}/{observedAt}.json}에 쓰는 문서.
 *
 * <p>여기서 읽는 것은 <b>어느 경기가, 무엇이 바뀌어, 언제</b>뿐이다. 점수·이닝·상태 값 자체도 문서에 있지만
 * 응답에는 쓰지 않는다 — 구독자에게 나가는 {@code game}은 {@code GET /api/games}와 같은 경로로 DB에서
 * 다시 읽는다({@link GameUpdateService}). 수집기는 DB upsert <b>직후</b>에 이 문서를 쓰므로 그 시점의 DB
 * 행은 문서와 같거나 더 새롭다. 두 표현(수집기 JSON ↔ {@code GameResponse})을 따로 맞춰 유지하지 않기
 * 위한 선택이다. 나머지 필드는 무시한다(수집기가 필드를 더해도 여기가 깨지지 않게).
 *
 * @param gameId     {@code games.naver_game_id}
 * @param changed    달라진 필드 이름 목록(수집기 판정)
 * @param observedAt 수집기 관측 시각(UTC ISO-8601)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameStateEventDocument(String gameId, List<String> changed, String observedAt) {
}
