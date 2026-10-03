package com.skhynix.quiz.quiz.settlement;

import com.skhynix.domain.game.entity.InningHalf;
import java.time.LocalDate;
import java.util.Map;

/**
 * py-collector(AI 레포, 별도 작업)가 이닝 종료 시점에 쓰는 S3 문서
 * ({@code inning-events/{date}/{gameId}/{inning}-{half}.json})의 역직렬화 DTO.
 *
 * <p>{@code gameId}는 naver_game_id 축이다({@link com.skhynix.quiz.quiz.ingest.QuizCandidate.Settlement#gameId()}
 * 와 같은 값 체계) — {@code GameRepository.findByNaverGameId}로 해석한다. {@code half}는
 * {@link InningHalf#name()} 문자열("TOP"/"BOTTOM")이다. {@code date}는 참고용(파티션 키)이며 정산
 * 판정에는 쓰지 않는다 — 판정은 {@code gameId}·{@code inning}·{@code half} 세 값만으로 끝난다.
 *
 * <p>{@code events}는 그 이닝에 관측된 선수의 {@code players.kbo_player_id} → {@link HitEvent} 맵이다.
 * 키가 없는 선수는 "안타를 치지 못함"과 "관측 자체가 없음"을 구분하지 않는다 — 이 지표
 * (BATTER_HIT_IN_INNING)는 둘 다 같은 결과(미적중)로 취급한다(스펙 범위, {@link QuizSettlementService}
 * 참고).
 */
public record InningEventFact(
        String gameId,
        LocalDate date,
        Integer inning,
        String half,
        Map<String, HitEvent> events) {

    public record HitEvent(boolean hit) {
    }
}
