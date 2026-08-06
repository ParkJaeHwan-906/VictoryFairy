package com.skhynix.user.player.dto;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.entity.PositionGroup;

/**
 * 선수 응답. {@code kboPlayerId} 는 py-collector 가 upsert 키로 소유하는 자연키라 미노출.
 *
 * <p>{@code playerNumber}·{@code playerPosition} 은 KBO 등록명단발이라 <b>null 이 그대로 나간다</b>
 * (등번호 미배정 육성선수, 1군 이력이 없어 포지션 구분이 비어 있는 선수). 서버가 대체값으로 채우지
 * 않는 것은 "값이 없다"와 "값이 UNKNOWN 이다"를 클라이언트가 구분할 수 있게 하기 위해서다.
 * {@code positionGroup} 은 nullable 이므로 enum 을 곧바로 문자열화하면 NPE 다 —
 * {@link #positionNameOf} 를 거친다.
 *
 * <p>{@code teamId}·{@code teamName} 을 담으면서 LAZY 인 {@code Player.team} 이 변환 시점에
 * 초기화된다. 따라서 이 변환은 <b>반드시 트랜잭션 안에서</b> 일어나야 하고(밖이면
 * {@code LazyInitializationException}), 1차 캐시가 흡수해도 서로 다른 구단 수만큼 추가 SELECT 가
 * 나간다. 목록이 커지면 리포지토리에 fetch join 을 도입할 자리다.
 */
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
