package com.skhynix.user.game.dto;


import com.skhynix.domain.game.entity.Game;

import java.time.LocalDateTime;

public record GameResponse(String gameId,
                           String stadium,
                           String homeTeam,
                           Long homeTeamId,
                           String awayTeam,
                           Long awayTeamId,
                           Integer homeTeamScore,
                           Integer awayTeamScore,
                           LocalDateTime gameDate,
                           String gameState,
                           String cancelReason,
                           Integer inning,
                           String inningHalf) {

    /**
     * {@code stadium} 은 {@code Game} 에서 유일하게 선택적인 연관({@code stadium_id} nullable)이라
     * {@code game.getStadium().getName()} 을 그대로 부르면 구장 미정 경기에서 NPE(500)가 난다. 점수와
     * 같은 방식으로 {@code null} 을 그대로 내보내고 표기는 클라이언트가 정한다.
     *
     * <p>{@code homeTeamId}/{@code awayTeamId} 는 라인업 응답({@code GameLineupResponse.teamId})을
     * 홈/원정에 대응시키기 위한 값이다 — 이 값이 없으면 클라이언트가 구단 이름 문자열 비교에 기대야 한다.
     * 두 FK 는 {@code optional = false} 라 null 검사가 필요 없고, id 접근은 프록시를 깨우지 않는 데다
     * {@code GameRepository} 의 {@code @EntityGraph} 가 이미 두 구단을 함께 읽으므로 SQL 이 늘지 않는다.
     *
     * <p>{@code cancelReason} 은 {@code gameState} 가 {@code CANCELED} 일 때만 값이 있고 그 외에는
     * {@code null} 이다. 연관이 아니라 {@code games} 의 일반 컬럼이라 {@code @EntityGraph} 를 손댈
     * 필요가 없다 — 연관을 더할 때만 그쪽과 1:1 로 맞춰야 한다.
     *
     * <p>{@code inning}/{@code inningHalf} 도 같은 이유로 {@code @EntityGraph} 와 무관한 일반 컬럼이며,
     * 진행 중이 아닌 경기에서는 둘 다 {@code null} 이다. {@code inningHalf} 는 {@code gameState} 가
     * {@code GameStatus.getName()} 문자열을 그대로 내보내는 것과 같은 방식으로 enum 이름을 내보낸다 —
     * ORDINAL 저장값(0/1)을 노출하면 선언 순서가 API 계약이 되어 버린다.
     */
    public static GameResponse from(Game game) {
        return new GameResponse(game.getNaverGameId(),
                game.getStadium() == null ? null : game.getStadium().getName(),
                game.getHomeTeam().getName(),
                game.getHomeTeam().getId(),
                game.getAwayTeam().getName(),
                game.getAwayTeam().getId(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getGameDate(),
                game.getGameStatus().getName(),
                game.getCancelReason(),
                game.getCurrentInning(),
                game.getInningHalf() == null ? null : game.getInningHalf().name()
        );
    }
}
