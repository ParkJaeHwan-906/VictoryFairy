package com.skhynix.user.team.dto;

import com.skhynix.domain.team.entity.Team;

/**
 * 구단(팀) 응답. 노출 필드는 {@code id}·{@code name} 뿐이다.
 *
 * <p>{@code Team.code} 는 py-collector 가 upsert 키로 소유하는 소스 자연키라 외부 계약이 되면 안 되므로,
 * 엔티티를 그대로 직렬화하지 않고 이 DTO 로 변환해 반환한다. {@code createdAt}/{@code updatedAt} 도 같은
 * 이유로 제외한다.
 *
 * @param id   구단 PK
 * @param name 구단 이름
 */
public record TeamResponse(Long id, String name) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName());
    }
}
