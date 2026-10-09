package com.skhynix.chat.room.dto;

import com.skhynix.domain.game.entity.Game;
import java.time.LocalDateTime;

/**
 * 방 목록 항목·상세(CHAT-GC-26·27). user 앱 GameResponse 와 같은 이름·의미를 쓴다.
 * 연관 엔티티(구단·상태)를 함께 읽어 온 Game 으로만 만들 것. 트랜잭션 밖에서 LAZY 프록시를 건드리게 된다.
 */
public record RoomResponse(
        String gameId,
        String homeTeam,
        Long homeTeamId,
        String awayTeam,
        Long awayTeamId,
        LocalDateTime gameDate,
        String gameState) {

    public static RoomResponse from(Game game) {
        return new RoomResponse(
                game.getNaverGameId(),
                game.getHomeTeam().getName(),
                game.getHomeTeam().getId(),
                game.getAwayTeam().getName(),
                game.getAwayTeam().getId(),
                game.getGameDate(),
                game.getGameStatus().getName());
    }
}
