package com.skhynix.user.account.dto;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;

// UserAccount 를 그대로 직렬화하면 password 해시·uid 가 함께 나간다 — 노출 키는 이 6개로 닫아 둔다.
// supportPlayers 는 응원 API 와 같은 PlayerResponse 재사용이라, 그쪽을 고치면 이 응답도 함께 바뀐다.
// profileImgUrl 은 BaseURL 을 뺀 EP 다(업로드 응답과 문자 그대로 같은 형태). 이미지가 없으면 null 이며
// 빈 문자열도 기본 이미지 URL 도 아니다 — supportTeam 이 null 인 것과 같은 방식이다.
// 이 record 는 /me 전용이라 필드 추가가 파괴적이지 않다(여러 곳이 공유하는 PlayerResponse 와 다르다).
public record UserAccountResponse(String nickname, TeamResponse supportTeam,
                                  List<PlayerResponse> supportPlayers, long point, long bqScore,
                                  String profileImgUrl) {

    public static UserAccountResponse of(UserAccount account, TeamResponse supportTeam,
                                         List<PlayerResponse> supportPlayers, long bqScore) {
        return new UserAccountResponse(
                account.getNickname(),
                supportTeam,
                supportPlayers,
                account.getPoint(),
                bqScore,
                // 이미 조회한 users_account 행에 들어 있는 값이라 SELECT 가 늘지 않는다.
                account.getProfileImgUrl());
    }
}
