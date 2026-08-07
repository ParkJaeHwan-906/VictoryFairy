package com.skhynix.user.team.dto;

import com.skhynix.domain.team.entity.Team;

/**
 * 구단(팀) 응답. {@code Team.code}(py-collector 소유 자연키)·{@code createdAt}/{@code updatedAt} 은
 * 외부 계약이 되면 안 되므로 미노출.
 */
public record TeamResponse(Long id, String name) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName());
    }
}
