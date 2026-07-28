package com.skhynix.user.player.dto;

import com.skhynix.domain.player.entity.Player;

/**
 * 선수 응답. 노출 필드는 {@code id}·{@code name} 뿐이다.
 *
 * <p>{@code Player.naverPcode}/{@code kboPlayerId} 는 py-collector 가 upsert 키로 소유하는 소스 자연키라
 * 외부 계약이 되면 안 되므로, 엔티티를 그대로 직렬화하지 않고 이 DTO 로 변환해 반환한다.
 * {@code average}·{@code createdAt}/{@code updatedAt} 도 아직 계약에 없어 제외한다.
 *
 * <p>{@code team} 을 담지 않는 것은 성능상으로도 의도적이다 — {@code Player.team} 은 LAZY 라 여기서
 * 건드리지 않는 한 선수 수만큼 팀 조회가 나가는 N+1 이 생기지 않는다. 나중에 팀 정보를 응답에 넣게 되면
 * 리포지토리에 fetch join 을 함께 도입해야 한다.
 *
 * <p>구단 응답인 {@code com.skhynix.user.team.dto.TeamResponse} 와 같은 자리(앱 모듈의 {@code dto}
 * 패키지)에 둔다. API 계약이라 공유 모듈인 {@code :domain} 에 두면 quiz 까지 끌려간다.
 *
 * @param id   선수 PK
 * @param name 선수 이름
 */
public record PlayerResponse(Long id, String name) {

    public static PlayerResponse from(Player player) {
        return new PlayerResponse(player.getId(), player.getName());
    }
}
