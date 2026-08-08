package com.skhynix.user.account.dto;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;

/**
 * 내 요약 프로필 응답({@code GET /api/users/me}). 노출 키는 정확히 이 5개로 닫혀 있다
 * ({@code docs/requirements/user/me-profile.md}) — {@link UserAccount} 를 그대로 직렬화하면
 * {@code password}·{@code uid} 가 함께 나간다.
 *
 * <p>{@code supportPlayers} 는 응원 API 와 같은 {@link PlayerResponse} 를 재사용한다. 프로필 전용
 * 선수 DTO 를 두면 같은 자원이 경로마다 다른 모양으로 나가고 키 변경 때 두 곳을 맞춰야 한다.
 * 대가로 <b>{@code PlayerResponse} 를 고치면 이 응답도 함께 바뀐다</b>.
 *
 * @param nickname       계정 닉네임
 * @param supportTeam    현재 응원 중인 구단. 없으면 {@code null}(안전망)
 * @param supportPlayers 현재 응원 중인 선수. 없으면 {@code null} 이 아니라 <b>빈 배열</b>이다 —
 *                       {@code supportTeam} 이 단일 값이라 "없음"을 {@code null} 로 표현할 수밖에 없는 것과
 *                       달리, 목록은 빈 배열이 그대로 "0건"을 뜻해 클라이언트가 null 검사 없이 순회할 수 있다
 * @param point          보유 포인트
 * @param bqScore        누적 획득 점수. {@code users_bq} 행이 없으면 0(안전망)
 */
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
