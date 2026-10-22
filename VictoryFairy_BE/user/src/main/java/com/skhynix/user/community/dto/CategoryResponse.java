package com.skhynix.user.community.dto;

import com.skhynix.domain.community.entity.CommunityCategory;

/** 자유게시판은 {@code teamId: null}. */
public record CategoryResponse(Long id, String name, Long teamId) {

    public static CategoryResponse from(CommunityCategory category) {
        // team 은 LAZY 프록시지만 id 접근은 초기화를 일으키지 않는다
        Long teamId = category.getTeam() == null ? null : category.getTeam().getId();
        return new CategoryResponse(category.getId(), category.getName(), teamId);
    }
}
