package com.skhynix.user.oauth.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserOauthLink;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserOauthLinkRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import com.skhynix.user.oauth.client.OauthUserInfo;
import com.skhynix.user.oauth.dto.OauthAuthResponse;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>지금 온 이 소셜 신원이 기존의 누구인가</b>를 판정하고, 판정이 통합으로 끝나면 연동 행까지 만든다.
 *
 * <p>이 기능의 무게는 토큰 교환이 아니라 여기에 실린다 — 판정이 틀리면 결과가 로그인 실패가 아니라
 * <b>계정 탈취</b>다. 판정 순서는 ①(provider, provider 사용자 식별자) 일치 ②확정된 이메일과
 * {@code users.email} 일치이며, 순서를 뒤집으면 provider 쪽에서 이메일을 바꾼 사용자가 자기 계정을
 * 잃는다.
 *
 * <p>판정과 연동 생성이 한 클래스·한 트랜잭션인 이유는 둘을 나눌 수 없어서가 아니라, 나누면 "찾았다"와
 * "붙였다" 사이에 다른 트랜잭션이 끼어들 수 있기 때문이다. 다만 그 창을 실제로 닫는 것은 이 트랜잭션이
 * 아니라 연동 테이블의 UNIQUE 두 개다(동시 요청 중 한 건은 제약 위반으로 500 이 되고 재시도하면 ①로
 * 성공한다).
 *
 * <p>provider HTTP 호출은 여기 들어오지 않는다 — 트랜잭션 안에서 외부 응답을 기다리면 DB 커넥션이
 * 그동안 묶인다.
 */
@Service
@RequiredArgsConstructor
public class OauthIdentityResolver {

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserOauthLinkRepository userOauthLinkRepository;
    private final OauthTicketStore ticketStore;
    private final AuthService authService;
    private final Clock clock;

    /**
     * provider 사용자 정보로 하는 최초 해석.
     *
     * <p>이메일 입력 단계로 갈지 말지는 <b>provider 종류가 아니라 이번 응답에 쓸 수 있는 이메일이
     * 있는지</b>로 갈린다. ⚠ {@code if (provider == KAKAO)} 로 바꾸지 말 것 — 카카오 비즈 앱 전환이
     * 승인되면 이메일이 오기 시작하는데, 그때 이 코드가 그대로여야 입력 단계가 <b>저절로</b> 사라진다.
     */
    @Transactional
    public OauthAuthResponse resolve(OauthProvider provider, OauthUserInfo info) {
        Optional<UserOauthLink> linked =
                userOauthLinkRepository.findByProviderAndProviderUserId(provider, info.providerUserId());
        if (linked.isPresent()) {
            UserAccount account = linked.get().getUserAccount();
            // 탈퇴 계정을 "못 찾음"으로 흡수하지 않는다. 여기서는 provider 가 이미 그 신원의 주인임을
            // 증명한 상태라 이메일 점유 사실을 알려 주는 것이 요청자 자신의 정보다(탈퇴 비노출의 예외).
            if (account.isWithdrawn()) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            // 이메일은 보지 않는다 — provider 쪽에서 이메일을 바꿨어도 같은 사람이고, 이메일을 안 주는
            // provider 도 두 번째 로그인부터는 이 경로라 입력 단계가 없다.
            return OauthAuthResponse.login(issueTokens(account));
        }

        if (!info.hasEmail()) {
            return OauthAuthResponse.emailInputRequired(
                    ticketStore.issue(OauthTicket.input(provider, info.providerUserId())));
        }

        String email = info.email();
        Optional<UserAccount> account = findActiveByEmail(email);
        if (account.isPresent()) {
            // 통합은 양쪽이 모두 검증됨일 때만 무조건이다. 한쪽이라도 미검증이면 남의 이메일을 등록한
            // 소셜 계정 하나로 그 사람 계정에 들어올 수 있어, 우리 인증번호로 소유를 증명받는다.
            if (info.emailVerified() && account.get().getUser().isEmailVerified()) {
                return OauthAuthResponse.login(link(account.get(), provider, info.providerUserId(), false));
            }
            return OauthAuthResponse.emailVerificationRequired(
                    ticketStore.issue(OauthTicket.link(provider, info.providerUserId(), email)), email);
        }

        ensureEmailNotOccupied(email);
        return OauthAuthResponse.signupRequired(ticketStore.issue(
                OauthTicket.signup(provider, info.providerUserId(), email, info.emailVerified())), email);
    }

    /**
     * 우리 인증번호를 통과한 이메일로 하는 해석 — 최초 해석의 ②단계와 <b>완전히 같다</b>.
     *
     * <p>⚠ 여기서 다시 인증번호를 요구하지 말 것. 이 이메일은 provider 가 준 미검증 이메일과 달리
     * 우리가 직접 소유를 확인한 값이라 신뢰도가 provider 검증 이메일과 동급 이상이고, 기존 계정이
     * 미검증이더라도 방금 통과한 인증이 곧 그 계정이 요구하는 증명이다(이중 인증 금지).
     */
    @Transactional
    public OauthAuthResponse resolveWithProvenEmail(OauthProvider provider, String providerUserId,
            String email) {
        Optional<UserAccount> account = findActiveByEmail(email);
        if (account.isPresent()) {
            return OauthAuthResponse.login(link(account.get(), provider, providerUserId, true));
        }

        ensureEmailNotOccupied(email);
        return OauthAuthResponse.signupRequired(
                ticketStore.issue(OauthTicket.signup(provider, providerUserId, email, true)), email);
    }

    /**
     * 기존 계정에 이 소셜 신원을 붙이고 토큰을 발급한다.
     *
     * @param emailOwnershipProven 우리 인증번호로 이메일 소유가 증명된 경로인가. provider 가 검증된
     *                             이메일을 준 경로는 {@code false} 여도 결과가 같다 — 그때 계정은 이미
     *                             검증됨이라 승격할 것이 없다
     */
    private TokenResponse link(UserAccount account, OauthProvider provider, String providerUserId,
            boolean emailOwnershipProven) {
        User user = account.getUser();
        // ⚠ verifyEmail() 은 직전 상태를 지운다. "미검증이던 계정이었는가"가 아래 선점 해제의 유일한
        //   조건이라 반드시 부르기 전에 읽는다.
        boolean wasUnverified = !user.isEmailVerified();

        if (emailOwnershipProven && wasUnverified) {
            user.verifyEmail();
            // 선점 방지: 승격 이전의 연동이 진짜 주인 것인지 선점자 것인지 구분할 수단이 없어 전부 끊는다.
            // 이것이 없으면 "미검증이어도 신규 가입은 통과시킨다"는 완화가 그대로 계정 탈취 경로가 된다.
            // ⚠ 순서가 계약이다 — 아래 save 뒤로 옮기면 이 벌크 삭제가 대기 중인 INSERT 를 먼저 flush 한
            //   다음 지워 방금 만든 연동까지 사라진다(트랜잭션은 커밋되고 연동만 없어지는 조용한 실패).
            userOauthLinkRepository.deleteAllByUserAccountId(account.getId());
        } else if (userOauthLinkRepository.existsByUserAccount_IdAndProvider(account.getId(), provider)) {
            // 같은 provider 의 '다른' 식별자다(같은 식별자였다면 ①에서 이미 찾혔다). 위 갈래에서 이
            // 검사를 안 하는 이유는 그쪽이 기존 연동을 통째로 지워 충돌 자체가 남지 않기 때문이다.
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ALREADY_LINKED);
        }

        userOauthLinkRepository.save(UserOauthLink.builder()
                .userAccount(account)
                .provider(provider)
                .providerUserId(providerUserId)
                .build());

        // 기존 계정의 email·nickname·name·tel·gender·profile_img_url 은 건드리지 않는다 — 통합은
        // "이 소셜로도 들어올 수 있다"는 사실만 추가하는 일이지 프로필을 provider 값으로 맞추는 일이 아니다.
        return issueTokens(account);
    }

    private Optional<UserAccount> findActiveByEmail(String email) {
        if (UnknownAccountPolicy.EMAIL.equalsIgnoreCase(email)) {
            // 탈퇴자 콘텐츠를 넘겨받는 더미 계정은 사람의 계정이 아니다. 해석 대상에서 빼되 이메일은
            // 실제로 점유돼 있으므로 신규 가입으로도 흘려보내지 않는다.
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        return userAccountRepository.findByUser_EmailAndExitAtIsNull(email);
    }

    /**
     * 활성 계정이 없는데 이메일은 이미 있는 경우 = 탈퇴 계정이 점유 중이다. {@code users.email} 의
     * UNIQUE 는 탈퇴를 구분하지 않아 어차피 가입이 실패하므로, 티켓을 주지 말고 여기서 끝낸다
     * (30일 뒤 하드 삭제되면 그때부터 신규 가입으로 열린다).
     */
    private void ensureEmailNotOccupied(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
    }

    // 소셜 로그인도 계정당 유효 refresh 1개 정책을 그대로 탄다(기존 세션은 끊긴다). claim 구성도 동일.
    private TokenResponse issueTokens(UserAccount account) {
        return authService.issueTokens(account, LocalDateTime.now(clock));
    }
}
