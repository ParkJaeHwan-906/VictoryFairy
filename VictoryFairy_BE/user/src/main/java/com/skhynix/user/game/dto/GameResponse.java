package com.skhynix.user.game.dto;


import com.skhynix.domain.game.entity.Game;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                           String inningHalf,
                           Double homeTeamWinRate,
                           Double awayTeamWinRate) {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final BigDecimal MIN_WIN_RATE = BigDecimal.ZERO;
    private static final BigDecimal MAX_WIN_RATE = BigDecimal.valueOf(100);

    // homeTeamId/awayTeamId 는 id 접근이라 프록시를 깨우지 않고 GameRepository 의 @EntityGraph 가 이미
    // 두 구단을 읽으므로 SQL 이 늘지 않는다. inningHalf 는 ORDINAL 저장값(0/1) 대신 enum 이름을
    // 내보낸다 — 저장값을 노출하면 선언 순서가 API 계약이 되어 버린다.
    public static GameResponse from(Game game) {
        String gameState = game.getGameStatus().getName();
        boolean showWinRate = isDisplayableWinRate(gameState, game.getHomeWinRate(), game.getAwayWinRate());
        return new GameResponse(game.getNaverGameId(),
                game.getStadium() == null ? null : game.getStadium().getName(),
                game.getHomeTeam().getName(),
                game.getHomeTeam().getId(),
                game.getAwayTeam().getName(),
                game.getAwayTeam().getId(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getGameDate(),
                gameState,
                game.getCancelReason(),
                game.getCurrentInning(),
                game.getInningHalf() == null ? null : game.getInningHalf().name(),
                showWinRate ? toPercent(game.getHomeWinRate()) : null,
                showWinRate ? toPercent(game.getAwayWinRate()) : null
        );
    }

    // 승률은 둘 다 보이거나 둘 다 숨긴다 — 한쪽만 보여 주면 화면이 반쪽짜리 막대를 그린다.
    // 상태 판정을 수집기의 NULL 쓰기에만 맡기지 않는 이유: 수집기가 종료 전이에서 NULL 로 덮지 못하면
    // 종료 경기에 마지막 승률이 남는다. 범위 검사는 DB CHECK 를 일부러 두지 않은 것의 대체다.
    private static boolean isDisplayableWinRate(String gameState, BigDecimal home, BigDecimal away) {
        return IN_PROGRESS.equals(gameState) && inRange(home) && inRange(away);
    }

    private static boolean inRange(BigDecimal rate) {
        return rate != null && rate.compareTo(MIN_WIN_RATE) >= 0 && rate.compareTo(MAX_WIN_RATE) <= 0;
    }

    // 컬럼이 DECIMAL(4,1) 이면 이미 한 자리지만, 환경마다 컬럼 타입이 어긋나도 응답 계약(소수 첫째 자리)은
    // 지키려고 다시 맞춘다.
    private static Double toPercent(BigDecimal rate) {
        return rate.setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
