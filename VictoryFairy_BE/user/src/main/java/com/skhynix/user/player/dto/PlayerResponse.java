package com.skhynix.user.player.dto;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.entity.PositionGroup;

// teamId·teamName 을 담느라 LAZY 인 Player.team 이 변환 시점에 초기화된다 — 이 변환은 반드시
// 트랜잭션 안에서 일어나야 하고(밖이면 LazyInitializationException), 구단 수만큼 추가 SELECT 가 나간다.
public record PlayerResponse(Long teamId, String teamName,
                             Long playerId, String playerName, String playerNumber, String playerPosition) {

    public static PlayerResponse from(Player player) {
        return new PlayerResponse(player.getTeam().getId(), player.getTeam().getName(),
                player.getId(), player.getName(), player.getUniformNumber(),
                positionNameOf(player.getPositionGroup()));
    }

    private static String positionNameOf(PositionGroup positionGroup) {
        return positionGroup == null ? null : positionGroup.name();
    }
}
