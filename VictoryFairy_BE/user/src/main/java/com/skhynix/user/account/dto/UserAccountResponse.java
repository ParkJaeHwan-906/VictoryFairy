package com.skhynix.user.account.dto;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.team.dto.TeamResponse;

/**
 * 내 요약 프로필 응답({@code GET /api/member/users/me}). 노출 키는 정확히 이 4개로 닫혀 있다
 * ({@code docs/requirements/user/me-profile.md}) — {@link UserAccount} 를 그대로 직렬화하면
 * {@code password}·{@code uid} 가 함께 나간다.
 *
 * @param nickname    계정 닉네임
 * @param supportTeam 현재 응원 중인 구단. 없으면 {@code null}(안전망)
 * @param point       보유 포인트
 * @param bqScore     누적 획득 점수. {@code users_bq} 행이 없으면 0(안전망)
 */
public record UserAccountResponse(String nickname, TeamResponse supportTeam, long point, long bqScore) {

    public static UserAccountResponse of(UserAccount account, TeamResponse supportTeam, long bqScore) {
        return new UserAccountResponse(
                account.getNickname(),
                supportTeam,
                account.getPoint(),
                bqScore);
    }
}
