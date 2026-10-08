package com.skhynix.user.ranking.dto;

import com.skhynix.domain.user.repository.BqRankingEntryView;

// 순위표 한 줄. 계정 식별자(id·uid)·isMe 는 싣지 않는다 — 본인 순위는 /rankings/bq/me 가 따로 낸다.
// profileImgUrl 은 BaseURL 을 뺀 EP 그대로이며 객체 실존을 확인하지 않는다(/me·채팅과 같은 규칙).
// 탈퇴 계정의 EP 는 이미 지워진 객체를 가리킬 수 있어 프론트 폴백이 전제다.
public record BqRankingResponse(int rank, String profileImgUrl, String nickname, long bqScore) {

    public static BqRankingResponse of(int rank, BqRankingEntryView entry) {
        return new BqRankingResponse(rank, entry.getProfileImgUrl(), entry.getNickname(),
                entry.getBqScore());
    }
}
