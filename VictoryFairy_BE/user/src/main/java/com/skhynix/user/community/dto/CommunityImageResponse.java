package com.skhynix.user.community.dto;

/** 업로드 응답 — 값은 {@code temp/{uuid}.{ext}} EP(BaseURL 없음). 글에 붙이면 {@code community/} 로 바뀐다. */
public record CommunityImageResponse(String imageUrl) {
}
