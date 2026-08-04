package com.skhynix.user.game.dto;


import com.skhynix.domain.game.entity.Game;

import java.time.LocalDateTime;

public record GameResponse(String gameId,
                           String stadium,
                           String homeTeam,
                           String awayTeam,
                           Integer homeTeamScore,
                           Integer awayTeamScore,
                           LocalDateTime gameDate,
                           String gameState) {

    /**
     * {@code stadium} 은 {@code Game} 에서 유일하게 선택적인 연관({@code stadium_id} nullable)이라
     * {@code game.getStadium().getName()} 을 그대로 부르면 구장 미정 경기에서 NPE(500)가 난다. 점수와
     * 같은 방식으로 {@code null} 을 그대로 내보내고 표기는 클라이언트가 정한다.
     */
    public static GameResponse from(Game game) {
        return new GameResponse(game.getNaverGameId(),
                game.getStadium() == null ? null : game.getStadium().getName(),
                game.getHomeTeam().getName(),
                game.getAwayTeam().getName(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getGameDate(),
                game.getGameStatus().getName()
        );
    }
}
