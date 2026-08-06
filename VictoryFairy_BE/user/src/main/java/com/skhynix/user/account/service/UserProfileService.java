package com.skhynix.user.account.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.team.dto.TeamResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 프로필 조회 서비스. 요구사항: {@code docs/requirements/user/me-profile.md}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserProfileService {

    private final UserAccountRepository userAccountRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final UserBqRepository userBqRepository;

    /**
     * 토큰 주체 본인의 요약 프로필(닉네임 · 응원 구단 · 보유 포인트 · 누적 획득 점수)을 조회한다.
     *
     * <p>DTO 조립을 이 트랜잭션 안에서 끝낸다 — 컨트롤러로 옮기면 {@code UserSupportTeam.team}
     * 의 LAZY 프록시가 {@code open-in-view: false} 인 prod 에서만 {@code LazyInitializationException} 으로
     * 터진다(dev 는 이 설정이 없어 로컬에서는 통과한다).
     *
     * @param userAccountId 인증된 요청의 principal({@code JwtAuthenticationFilter} 가 uid 를 해석해 넣은
     *                      내부 PK). 탈퇴 계정은 그 해석 단계에서 걸러져 여기 닿지 않는다
     */
    public UserAccountResponse getMyProfile(Long userAccountId) {
        // 필터가 활성 계정임을 확인한 id라 정상 경로에서는 항상 존재한다. 그 사이 사라졌다면 인증 근거가
        // 사라진 것이므로 필터가 못 찾았을 때와 같은 401로 맞춘다(UserAccountService.withdraw 와 동일).
        UserAccount account = userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // 응원 구단이 없으면 예외가 아니라 null 이다(안전망) — 활성 구단 행이 2개 이상인
        // 깨진 데이터는 리포지토리가 예외로 드러낸다.
        TeamResponse supportTeam = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> TeamResponse.from(support.getTeam()))
                .orElse(null);

        // 행이 없으면 0이다(안전망) — 배포~백필 사이 구간에서도 200을 유지하며, 행을 만들지는 않는다.
        long bqScore = userBqRepository.findByUserAccount_Id(userAccountId)
                .map(UserBq::getBqScore)
                .orElse(0L);

        return UserAccountResponse.of(account, supportTeam, bqScore);
    }
}
