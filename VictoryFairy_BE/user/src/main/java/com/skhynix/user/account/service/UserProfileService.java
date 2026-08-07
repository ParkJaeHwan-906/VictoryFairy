package com.skhynix.user.account.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.support.service.SupportService;
import com.skhynix.user.team.dto.TeamResponse;
import java.util.List;
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
     * 응원 선수 목록은 리포지토리를 직접 잡지 않고 이 서비스에 위임한다. 선수·구단까지 fetch join 해
     * N+1 없이 한 번에 가져오는 조회가 이미 거기 있고, 같은 목록을 두 곳에서 만들면 한쪽만 고쳐질 때
     * 응원 API 응답과 프로필 응답이 갈라진다.
     */
    private final SupportService supportService;

    /**
     * 토큰 주체 본인의 요약 프로필(닉네임 · 응원 구단 · 응원 선수 · 보유 포인트 · 누적 획득 점수)을 조회한다.
     *
     * <p>DTO 조립을 이 트랜잭션 안에서 끝낸다 — 컨트롤러로 옮기면 {@code UserSupportTeam.team}
     * 과 {@code Player.team} 의 LAZY 프록시가 {@code open-in-view: false} 인 prod 에서만
     * {@code LazyInitializationException} 으로 터진다(dev 는 이 설정이 없어 로컬에서는 통과한다).
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

        // 응원 구단 유무를 따지지 않는다 — 응원 선수 조회는 구단 행을 전제하지 않아 구단 미선택 계정도
        // 예외 없이 빈 목록으로 떨어진다(supportTeam == null 인 안전망 경로가 그대로 200을 유지한다).
        List<PlayerResponse> supportPlayers = supportService.currentSupportedPlayers(userAccountId);

        // 행이 없으면 0이다(안전망) — 배포~백필 사이 구간에서도 200을 유지하며, 행을 만들지는 않는다.
        long bqScore = userBqRepository.findByUserAccount_Id(userAccountId)
                .map(UserBq::getBqScore)
                .orElse(0L);

        return UserAccountResponse.of(account, supportTeam, supportPlayers, bqScore);
    }
}
