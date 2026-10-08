package com.skhynix.user.community.dto;

import com.skhynix.domain.user.entity.UserAccount;

/**
 * 작성자 — 닉네임과 프로필 이미지 EP 뿐이다. 계정 {@code id}·{@code uid} 는 싣지 않는다.
 * {@code profileImgUrl} 은 BaseURL 없는 EP 또는 {@code null}(채팅 MessageResponse 와 같은 규칙).
 */
public record AuthorResponse(String nickname, String profileImgUrl) {

    // 호출자는 트랜잭션 안에서(또는 fetch 된 엔티티로) 불러야 한다 — LAZY 프록시를 여기서 초기화한다
    public static AuthorResponse from(UserAccount account) {
        return new AuthorResponse(account.getNickname(), account.getProfileImgUrl());
    }
}
