package com.skhynix.user.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.Gender;
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
import com.skhynix.user.oauth.dto.OauthAuthStatus;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import com.skhynix.user.oauth.store.OauthTicketType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OauthIdentityResolver} — "지금 온 이 소셜 신원이 기존의 누구인가"를 판정하는 이 기능의 핵심.
 * 판정이 틀리면 로그인 실패가 아니라 계정 탈취라, 우선순위(USER-OAU-13)·선점 방지 순서(USER-OAU-73)·
 * 이중 인증 금지(USER-OAU-97)를 특히 무겁게 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class OauthIdentityResolverTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserOauthLinkRepository userOauthLinkRepository;

    @Mock
    private OauthTicketStore ticketStore;

    @Mock
    private AuthService authService;

    private OauthIdentityResolver newResolver() {
        return new OauthIdentityResolver(userRepository, userAccountRepository, userOauthLinkRepository,
                ticketStore, authService, CLOCK);
    }

    private User verifiedUser(String email) {
        return User.builder().name("홍길동").tel("01012345678").email(email).gender(Gender.MALE).build();
    }

    private UserAccount accountOf(User user, String nickname) {
        return UserAccount.builder().user(user).nickname(nickname).password("encoded").build();
    }

    // ---------- resolve(): 우선순위 (USER-OAU-13, 14) ----------

    @Test
    @DisplayName("[USER-OAU-13, 14] (provider, 식별자) 연동 행이 있으면 이메일이 다른 계정을 가리켜도 그 연동의 "
            + "계정으로 로그인시키고, 이메일 대조 자체를 하지 않는다(userAccountRepository의 이메일 조회 0회)")
    void resolve_linkedIdentityFound_logsInWithoutEmailComparison() {
        // given
        User user = verifiedUser("account-owner@example.com");
        UserAccount account = accountOf(user, "userA");
        UserOauthLink link = UserOauthLink.builder()
                .userAccount(account).provider(OauthProvider.GOOGLE).providerUserId("sub-1").build();
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-1"))
                .willReturn(Optional.of(link));
        TokenResponse tokens = new TokenResponse("access", "refresh");
        given(authService.issueTokens(eq(account), any())).willReturn(tokens);
        // provider쪽 이메일이 완전히 다른 사람 것이어도 무관하다는 것을 보이기 위한 값
        OauthUserInfo info = new OauthUserInfo("sub-1", "someone-else@example.com", true);

        // when
        OauthAuthResponse response = newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.LOGIN);
        assertThat(response.accessToken()).isEqualTo("access");
        verify(userAccountRepository, never()).findByUser_EmailAndExitAtIsNull(anyString());
    }

    @Test
    @DisplayName("[USER-OAU-51] 연동 행이 가리키는 계정이 탈퇴 상태면 409 DUPLICATE_EMAIL을 던지고 토큰을 발급하지 않는다")
    void resolve_linkedAccountWithdrawn_throwsDuplicateEmailWithoutIssuingTokens() {
        // given
        User user = verifiedUser("withdrawn@example.com");
        UserAccount account = accountOf(user, "withdrawnUser");
        account.withdraw(java.time.LocalDateTime.now());
        UserOauthLink link = UserOauthLink.builder()
                .userAccount(account).provider(OauthProvider.KAKAO).providerUserId("kakao-1").build();
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, "kakao-1"))
                .willReturn(Optional.of(link));
        OauthUserInfo info = new OauthUserInfo("kakao-1", null, false);

        // when & then
        assertThatThrownBy(() -> newResolver().resolve(OauthProvider.KAKAO, info))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verifyNoInteractions(authService);
    }

    // ---------- resolve(): 이메일 미제공 (USER-OAU-90, 91) ----------

    private static Stream<OauthProvider> allProviders() {
        return Stream.of(OauthProvider.KAKAO, OauthProvider.NAVER, OauthProvider.GOOGLE);
    }

    @ParameterizedTest
    @MethodSource("allProviders")
    @DisplayName("[USER-OAU-90, 91] 연동이 없고 이번 응답에 이메일이 없으면(provider 종류와 무관하게) "
            + "200 EMAIL_INPUT_REQUIRED + 입력 티켓을 돌려주고 계정·이메일 조회를 시도하지 않는다")
    void resolve_noEmailAvailable_returnsEmailInputRequiredRegardlessOfProvider(OauthProvider provider) {
        // given
        given(userOauthLinkRepository.findByProviderAndProviderUserId(provider, "no-email-id"))
                .willReturn(Optional.empty());
        given(ticketStore.issue(OauthTicket.input(provider, "no-email-id"))).willReturn("input-ticket");
        OauthUserInfo info = new OauthUserInfo("no-email-id", null, false);

        // when
        OauthAuthResponse response = newResolver().resolve(provider, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.EMAIL_INPUT_REQUIRED);
        assertThat(response.ticket()).isEqualTo("input-ticket");
        assertThat(response.email()).isNull();
        assertThat(response.accessToken()).isNull();
        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(userRepository);
        verify(userOauthLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-OAU-29] EMAIL_INPUT_REQUIRED 응답 전후로 users_oauth_link에 아무 것도 쓰지 않는다"
            + "(티켓 발급 시점에 행을 만들지 않는다)")
    void resolve_noEmailAvailable_createsNoRows() {
        // given
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, "id-1"))
                .willReturn(Optional.empty());
        given(ticketStore.issue(any())).willReturn("input-ticket");
        OauthUserInfo info = new OauthUserInfo("id-1", "", false); // 빈 문자열도 이메일 없음으로 취급

        // when
        newResolver().resolve(OauthProvider.KAKAO, info);

        // then
        verify(userOauthLinkRepository, never()).save(any());
    }

    // ---------- resolve(): 이메일 자동 통합 — 양쪽 검증됨 (USER-OAU-16, 17, 21, 22) ----------

    @Test
    @DisplayName("[USER-OAU-16, 17] 연동은 없지만 같은 이메일의 활성 계정이 있고 provider·계정 양쪽 다 검증됨이면 "
            + "인증번호 없이 연동 1행을 만들고 그 계정의 토큰을 반환한다")
    void resolve_emailMatchBothVerified_autoLinksWithoutCode() {
        // given
        User user = verifiedUser("match@example.com");
        UserAccount account = accountOf(user, "existingNickname");
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-2"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("match@example.com"))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.existsByUserAccount_IdAndProvider(account.getId(), OauthProvider.GOOGLE))
                .willReturn(false);
        TokenResponse tokens = new TokenResponse("access", "refresh");
        given(authService.issueTokens(eq(account), any())).willReturn(tokens);
        OauthUserInfo info = new OauthUserInfo("sub-2", "match@example.com", true);

        // when
        OauthAuthResponse response = newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.LOGIN);
        assertThat(response.accessToken()).isEqualTo("access");
        ArgumentCaptor<UserOauthLink> captor = ArgumentCaptor.forClass(UserOauthLink.class);
        verify(userOauthLinkRepository).save(captor.capture());
        assertThat(captor.getValue().getUserAccount()).isSameAs(account);
        assertThat(captor.getValue().getProvider()).isEqualTo(OauthProvider.GOOGLE);
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("sub-2");
        // 선점 방지 삭제는 "미검증이던 계정"에서만 일어난다 — 이미 검증됨인 계정은 대상이 아니다.
        verify(userOauthLinkRepository, never()).deleteAllByUserAccountId(anyLong());
    }

    @Test
    @DisplayName("[USER-OAU-21, 22, 46] 자동 통합 후에도 기존 계정의 email·nickname·name·tel·gender·"
            + "profileImgUrl·password는 provider 값으로 바뀌지 않는다(연동 추가가 자체 로그인 비밀번호를 "
            + "건드리지 않는다)")
    void resolve_autoLink_doesNotOverwriteExistingProfileFields() {
        // given
        User user = verifiedUser("stable@example.com");
        UserAccount account = accountOf(user, "stableNickname");
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-3"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("stable@example.com"))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.existsByUserAccount_IdAndProvider(account.getId(), OauthProvider.GOOGLE))
                .willReturn(false);
        given(authService.issueTokens(eq(account), any())).willReturn(new TokenResponse("a", "r"));
        OauthUserInfo info = new OauthUserInfo("sub-3", "stable@example.com", true);

        // when
        newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        assertThat(account.getNickname()).isEqualTo("stableNickname");
        assertThat(account.getUser().getEmail()).isEqualTo("stable@example.com");
        assertThat(account.getUser().getName()).isEqualTo("홍길동");
        assertThat(account.getUser().getTel()).isEqualTo("01012345678");
        assertThat(account.getUser().getGender()).isEqualTo(Gender.MALE);
        assertThat(account.getProfileImgUrl()).isNull();
        assertThat(account.getPassword()).isEqualTo("encoded");
    }

    @Test
    @DisplayName("[USER-OAU-20] 이메일로 해석된 계정에 같은 provider의 다른 식별자 연동이 이미 있으면 409 "
            + "OAUTH_PROVIDER_ALREADY_LINKED를 던지고 새 연동을 저장하지 않는다")
    void resolve_sameProviderAlreadyLinkedWithDifferentIdentifier_throwsConflict() {
        // given
        User user = verifiedUser("conflict@example.com");
        UserAccount account = accountOf(user, "conflictUser");
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-new"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("conflict@example.com"))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.existsByUserAccount_IdAndProvider(account.getId(), OauthProvider.GOOGLE))
                .willReturn(true);
        OauthUserInfo info = new OauthUserInfo("sub-new", "conflict@example.com", true);

        // when & then
        assertThatThrownBy(() -> newResolver().resolve(OauthProvider.GOOGLE, info))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_ALREADY_LINKED);

        verify(userOauthLinkRepository, never()).save(any());
        verifyNoInteractions(authService);
    }

    // ---------- resolve(): 이메일 자동 통합 — 한쪽이라도 미검증 (USER-OAU-68) ----------

    @Test
    @DisplayName("[USER-OAU-68] provider 이메일이 미검증이면(계정은 검증됨이어도) 200 "
            + "EMAIL_VERIFICATION_REQUIRED + 링크 티켓을 돌려주고 연동·토큰 어느 쪽도 만들지 않는다")
    void resolve_providerEmailUnverified_returnsEmailVerificationRequired() {
        // given
        User user = verifiedUser("risky@example.com");
        UserAccount account = accountOf(user, "riskyUser");
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.NAVER, "naver-1"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("risky@example.com"))
                .willReturn(Optional.of(account));
        given(ticketStore.issue(OauthTicket.link(OauthProvider.NAVER, "naver-1", "risky@example.com")))
                .willReturn("link-ticket");
        OauthUserInfo info = new OauthUserInfo("naver-1", "risky@example.com", false);

        // when
        OauthAuthResponse response = newResolver().resolve(OauthProvider.NAVER, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.EMAIL_VERIFICATION_REQUIRED);
        assertThat(response.ticket()).isEqualTo("link-ticket");
        assertThat(response.email()).isEqualTo("risky@example.com");
        verify(userOauthLinkRepository, never()).save(any());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("[USER-OAU-68] provider 이메일은 검증됐지만 기존 계정 이메일이 미검증이면 여전히 "
            + "EMAIL_VERIFICATION_REQUIRED다(둘 다 검증됨이어야 자동 통합)")
    void resolve_existingAccountEmailUnverified_returnsEmailVerificationRequired() {
        // given
        User unverifiedUser = User.socialSignup("preempted@example.com", false);
        UserAccount account = accountOf(unverifiedUser, "preemptedUser");
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-5"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("preempted@example.com"))
                .willReturn(Optional.of(account));
        given(ticketStore.issue(any())).willReturn("link-ticket");
        OauthUserInfo info = new OauthUserInfo("sub-5", "preempted@example.com", true);

        // when
        OauthAuthResponse response = newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.EMAIL_VERIFICATION_REQUIRED);
        verify(userOauthLinkRepository, never()).save(any());
    }

    // ---------- resolve(): 신규 가입 티켓 (USER-OAU-12, 27, 52, 56) ----------

    @Test
    @DisplayName("[USER-OAU-12, 27] 연동도 없고 같은 이메일의 활성 계정도 없으면 200 SIGNUP_REQUIRED + 가입 "
            + "티켓을 돌려주고 users·users_account 어느 쪽도 만들지 않는다")
    void resolve_noMatchingAccount_returnsSignupRequired() {
        // given
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "new-sub"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("brandnew@example.com"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("brandnew@example.com")).willReturn(false);
        given(ticketStore.issue(OauthTicket.signup(OauthProvider.GOOGLE, "new-sub", "brandnew@example.com", true)))
                .willReturn("signup-ticket");
        OauthUserInfo info = new OauthUserInfo("new-sub", "brandnew@example.com", true);

        // when
        OauthAuthResponse response = newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.SIGNUP_REQUIRED);
        assertThat(response.ticket()).isEqualTo("signup-ticket");
        assertThat(response.email()).isEqualTo("brandnew@example.com");
        verifyNoInteractions(authService);
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-OAU-52] 활성 계정은 없지만 그 이메일이 탈퇴 계정에 점유돼 있으면 409 DUPLICATE_EMAIL을 "
            + "던지고 가입 티켓을 발급하지 않는다")
    void resolve_emailOccupiedByWithdrawnAccount_throwsDuplicateEmail() {
        // given
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-6"))
                .willReturn(Optional.empty());
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("occupied@example.com"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("occupied@example.com")).willReturn(true);
        OauthUserInfo info = new OauthUserInfo("sub-6", "occupied@example.com", true);

        // when & then
        assertThatThrownBy(() -> newResolver().resolve(OauthProvider.GOOGLE, info))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verifyNoInteractions(ticketStore);
    }

    @Test
    @DisplayName("[USER-OAU-56] 확정된 이메일이 (알수없음) 더미 계정의 예약 이메일이면 계정 조회 없이 409 "
            + "DUPLICATE_EMAIL을 던진다 — 더미 계정은 신원 해석 대상이 아니다")
    void resolve_reservedUnknownAccountEmail_throwsDuplicateEmailWithoutQueryingAccounts() {
        // given
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.KAKAO, "sub-7"))
                .willReturn(Optional.empty());
        OauthUserInfo info = new OauthUserInfo("sub-7", UnknownAccountPolicy.EMAIL, true);

        // when & then
        assertThatThrownBy(() -> newResolver().resolve(OauthProvider.KAKAO, info))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verify(userAccountRepository, never()).findByUser_EmailAndExitAtIsNull(anyString());
    }

    // ---------- resolveWithProvenEmail(): 선점 방지·이중 인증 금지 (USER-OAU-73, 74, 96, 97, 99) ----------

    @Test
    @DisplayName("[핵심 회귀 ①] 미검증이던 계정이 인증번호로 통합되면, 새 연동을 저장하기 '전에' 기존 연동을 "
            + "전량 해제한다 — 순서가 뒤집히면 대기 중인 INSERT가 flush된 뒤 삭제돼 방금 만든 연동까지 사라진다")
    void resolveWithProvenEmail_previouslyUnverifiedAccount_deletesExistingLinksBeforeSavingNewOne() {
        // given
        User unverifiedUser = User.socialSignup("preempted@example.com", false);
        UserAccount account = accountOf(unverifiedUser, "preemptedUser");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("preempted@example.com"))
                .willReturn(Optional.of(account));
        given(authService.issueTokens(eq(account), any())).willReturn(new TokenResponse("access", "refresh"));

        // when
        OauthAuthResponse response = newResolver()
                .resolveWithProvenEmail(OauthProvider.GOOGLE, "sub-genuine", "preempted@example.com");

        // then: 로그인 성공 + 계정이 검증됨으로 승격
        assertThat(response.status()).isEqualTo(OauthAuthStatus.LOGIN);
        assertThat(account.getUser().isEmailVerified()).isTrue();

        // 순서 고정 — deleteAllByUserAccountId가 save보다 먼저다
        InOrder order = inOrder(userOauthLinkRepository);
        order.verify(userOauthLinkRepository).deleteAllByUserAccountId(account.getId());
        order.verify(userOauthLinkRepository).save(any(UserOauthLink.class));

        // 승격 이후 갈래이므로 같은 provider 충돌 검사는 건너뛴다(기존을 통째로 지워 충돌 자체가 없다)
        verify(userOauthLinkRepository, never()).existsByUserAccount_IdAndProvider(anyLong(), any());
    }

    @Test
    @DisplayName("[USER-OAU-97] 이중 인증 금지 — 인증번호를 통과해 통합이 성립하면, 기존 계정이 미검증이었어도 "
            + "다시 EMAIL_VERIFICATION_REQUIRED로 되돌아가지 않고 곧바로 LOGIN이다")
    void resolveWithProvenEmail_previouslyUnverifiedAccount_doesNotRequireAdditionalVerification() {
        // given
        User unverifiedUser = User.socialSignup("nodoubleauth@example.com", false);
        UserAccount account = accountOf(unverifiedUser, "user");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("nodoubleauth@example.com"))
                .willReturn(Optional.of(account));
        given(authService.issueTokens(eq(account), any())).willReturn(new TokenResponse("a", "r"));

        // when
        OauthAuthResponse response = newResolver()
                .resolveWithProvenEmail(OauthProvider.NAVER, "naver-genuine", "nodoubleauth@example.com");

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.LOGIN);
        assertThat(response.ticket()).isNull();
        verifyNoInteractions(ticketStore);
    }

    @Test
    @DisplayName("[USER-OAU-74] 이미 검증됨이던 계정에서는 인증번호 통과로 통합돼도 기존 연동 행을 삭제하지 않는다")
    void resolveWithProvenEmail_alreadyVerifiedAccount_doesNotDeleteExistingLinks() {
        // given
        User verifiedUser = verifiedUser("already-verified@example.com");
        UserAccount account = accountOf(verifiedUser, "user");
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("already-verified@example.com"))
                .willReturn(Optional.of(account));
        given(userOauthLinkRepository.existsByUserAccount_IdAndProvider(account.getId(), OauthProvider.KAKAO))
                .willReturn(false);
        given(authService.issueTokens(eq(account), any())).willReturn(new TokenResponse("a", "r"));

        // when
        newResolver().resolveWithProvenEmail(OauthProvider.KAKAO, "kakao-new", "already-verified@example.com");

        // then
        verify(userOauthLinkRepository, never()).deleteAllByUserAccountId(anyLong());
        verify(userOauthLinkRepository).save(any(UserOauthLink.class));
    }

    @Test
    @DisplayName("[USER-OAU-96, 98] 인증번호를 통과한 이메일의 활성 계정이 없으면 200 SIGNUP_REQUIRED + 가입 "
            + "티켓을 돌려주고 계정 행을 만들지 않는다")
    void resolveWithProvenEmail_noActiveAccount_returnsSignupRequired() {
        // given
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("proven-new@example.com"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("proven-new@example.com")).willReturn(false);
        given(ticketStore.issue(any())).willReturn("signup-ticket");

        // when
        OauthAuthResponse response = newResolver()
                .resolveWithProvenEmail(OauthProvider.KAKAO, "kakao-8", "proven-new@example.com");

        // then
        assertThat(response.status()).isEqualTo(OauthAuthStatus.SIGNUP_REQUIRED);
        assertThat(response.email()).isEqualTo("proven-new@example.com");
        verify(userAccountRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-OAU-99] 입력 티켓 경로로 확정된 이메일의 가입 티켓은 emailVerified가 항상 true다"
            + "(우리가 직접 소유를 확인한 값이라 provider 판정과 무관하게 검증됨으로 취급)")
    void resolveWithProvenEmail_signupTicket_alwaysMarkedEmailVerifiedTrue() {
        // given
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("proven-signup@example.com"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("proven-signup@example.com")).willReturn(false);
        given(ticketStore.issue(any())).willReturn("signup-ticket");

        // when
        newResolver().resolveWithProvenEmail(OauthProvider.KAKAO, "kakao-9", "proven-signup@example.com");

        // then
        ArgumentCaptor<OauthTicket> captor = ArgumentCaptor.forClass(OauthTicket.class);
        verify(ticketStore).issue(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(OauthTicketType.SIGNUP);
        assertThat(captor.getValue().emailVerified()).isTrue();
        assertThat(captor.getValue().email()).isEqualTo("proven-signup@example.com");
    }

    @Test
    @DisplayName("[USER-OAU-52] 입력 티켓 경로에서도 확정된 이메일이 탈퇴 계정 점유분이면 409 DUPLICATE_EMAIL이다")
    void resolveWithProvenEmail_emailOccupiedByWithdrawnAccount_throwsDuplicateEmail() {
        // given
        given(userAccountRepository.findByUser_EmailAndExitAtIsNull("proven-occupied@example.com"))
                .willReturn(Optional.empty());
        given(userRepository.existsByEmail("proven-occupied@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> newResolver()
                .resolveWithProvenEmail(OauthProvider.GOOGLE, "sub-10", "proven-occupied@example.com"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    // ---------- 참고: Clock 오프셋 회귀 방지(존이 섞이지 않는지) ----------

    @Test
    @DisplayName("resolver는 issueTokens 호출에 주입된 Clock(Asia/Seoul)으로 계산한 시각을 넘긴다")
    void resolve_issueTokens_usesInjectedClockZone() {
        // given
        User user = verifiedUser("clock@example.com");
        UserAccount account = accountOf(user, "clockUser");
        UserOauthLink link = UserOauthLink.builder()
                .userAccount(account).provider(OauthProvider.GOOGLE).providerUserId("sub-clock").build();
        given(userOauthLinkRepository.findByProviderAndProviderUserId(OauthProvider.GOOGLE, "sub-clock"))
                .willReturn(Optional.of(link));
        given(authService.issueTokens(eq(account), any())).willReturn(new TokenResponse("a", "r"));
        OauthUserInfo info = new OauthUserInfo("sub-clock", null, false);

        // when
        newResolver().resolve(OauthProvider.GOOGLE, info);

        // then
        ArgumentCaptor<java.time.LocalDateTime> captor = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(authService).issueTokens(eq(account), captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                Instant.parse("2026-08-21T00:00:00Z").atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime());
    }
}
