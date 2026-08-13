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

    // homeTeamId/awayTeamId 는 id 접근이라 프록시를 깨우지 않고 GameRepository 의 @EntityGraph 가 이미
    // 두 구단을 읽으므로 SQL 이 늘지 않는다. inningHalf 는 ORDINAL 저장값(0/1) 대신 enum 이름을
    // 내보낸다 — 저장값을 노출하면 선언 순서가 API 계약이 되어 버린다.
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
