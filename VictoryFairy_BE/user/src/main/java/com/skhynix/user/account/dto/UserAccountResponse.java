package com.skhynix.user.account.dto;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;

// UserAccount 를 그대로 직렬화하면 password 해시·uid 가 함께 나간다 — 노출 키는 이 5개로 닫아 둔다.
// supportPlayers 는 응원 API 와 같은 PlayerResponse 재사용이라, 그쪽을 고치면 이 응답도 함께 바뀐다.
public record UserAccountResponse(String nickname, TeamResponse supportTeam,
                                  List<PlayerResponse> supportPlayers, long point, long bqScore) {

    public static UserAccountResponse of(UserAccount account, TeamResponse supportTeam,
                                         List<PlayerResponse> supportPlayers, long bqScore) {
        return new UserAccountResponse(
                account.getNickname(),
                supportTeam,
                supportPlayers,
                account.getPoint(),
                bqScore);
    }
}
