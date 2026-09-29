package com.skhynix.user.block.dto;

// 차단 생성(USER-BLK-9)·차단 목록 조회(USER-BLK-10) 공통 응답 한 줄. 대상 계정 식별자(id·uid)는
// 싣지 않는다 — 랭킹(BqRankingResponse)·채팅과 같은 은닉 기조(닉네임만으로 식별).
public record BlockResponse(String nickname) {
}
