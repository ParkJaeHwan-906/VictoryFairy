package com.skhynix.user.player.dto;

import com.skhynix.domain.player.entity.Player;

/**
 * 선수 응답. {@code naverPcode}/{@code kboPlayerId} 는 py-collector 소유 자연키라 미노출.
 *
 * <p>{@code team} 을 담지 않는 것은 성능상 의도적이다 — {@code Player.team} 은 LAZY 라 여기서 건드리지
 * 않는 한 N+1 이 생기지 않는다. 나중에 팀 정보를 넣게 되면 리포지토리에 fetch join 을 함께 도입해야 한다.
 */
public record PlayerResponse(Long id, String name) {

    public static PlayerResponse from(Player player) {
        return new PlayerResponse(player.getId(), player.getName());
    }
}
