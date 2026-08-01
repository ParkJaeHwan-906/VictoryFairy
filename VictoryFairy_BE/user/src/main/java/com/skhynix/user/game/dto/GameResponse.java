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
     * {@code stadium} 은 {@code Game} 에서 유일하게 선택적인 연관이다({@code optional = true},
     * {@code stadium_id} 가 nullable). 구장이 미정인 경기(예: 편성 전·중립구장 미확정)가 들어오면
     * {@code getStadium()} 이 {@code null} 이므로 그대로 {@code .getName()} 을 부르면 응답 전체가 500 이 된다.
     * 점수({@code homeTeamScore}/{@code awayTeamScore})가 경기 전에는 {@code null} 인 것과 같은 방식으로
     * {@code null} 을 그대로 내보내고, 표기는 클라이언트가 정한다.
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
