package com.skhynix.quiz.quizroom.dto;

import com.skhynix.domain.user.entity.UserAccount;

/** 작성자는 이 두 키로만 노출한다 — 계정 id·uid 는 응답 어디에도 싣지 않는다. */
public record AuthorResponse(String nickname, String profileImgUrl) {

    public static AuthorResponse from(UserAccount account) {
        return new AuthorResponse(account.getNickname(), account.getProfileImgUrl());
    }
}
