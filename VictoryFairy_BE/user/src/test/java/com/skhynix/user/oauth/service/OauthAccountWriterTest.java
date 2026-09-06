package com.skhynix.user.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserBq;
import com.skhynix.domain.user.entity.UserOauthLink;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserBqRepository;
import com.skhynix.domain.user.repository.UserOauthLinkRepository;
import com.skhynix.domain.user.repository.UserRepository;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.character.service.DefaultCharacterGrantService;
import com.skhynix.user.oauth.policy.SocialAccountPolicy;
import com.skhynix.user.oauth.store.OauthTicket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OauthAccountWriter} — 소셜 신규 가입의 트랜잭션 단위. 요구사항:
 * {@code docs/requirements/user/oauth-login.md} USER-OAU-30~38, 40~42, 86.
 */
@ExtendWith(MockitoExtension.class)
class OauthAccountWriterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserBqRepository userBqRepository;

    @Mock
    private UserOauthLinkRepository userOauthLinkRepository;

    @Mock
    private AuthService authService;

    @Mock
    private DefaultCharacterGrantService defaultCharacterGrantService;

    private OauthAccountWriter newWriter() {
        return new OauthAccountWriter(userRepository, userAccountRepository, userBqRepository,
                userOauthLinkRepository, authService, defaultCharacterGrantService, CLOCK);
    }

    private OauthTicket signupTicket(String email, boolean emailVerified) {
        return OauthTicket.signup(OauthProvider.GOOGLE, "sub-1", email, emailVerified);
    }

    @Test
    @DisplayName("[USER-OAU-40] 티켓의 이메일이 이미 점유돼 있으면 409 DUPLICATE_EMAIL을 던지고 어떤 행도 만들지 않는다")
    void createAccount_emailAlreadyOccupied_throwsDuplicateEmailWithoutSaving() {
        // given
        OauthTicket ticket = signupTicket("taken@example.com", true);
        given(userRepository.existsByEmail("taken@example.com")).willReturn(true);
        OauthAccountWriter writer = newWriter();

        // when & then
        assertThatThrownBy(() -> writer.createAccount(ticket, "승리요정"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

        verify(userRepository, never()).save(any());
        verify(userAccountRepository, never()).save(any());
        verify(userBqRepository, never()).save(any());
        verify(userOauthLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-OAU-38] 닉네임이 이미 점유돼 있으면 409 DUPLICATE_NICKNAME을 던지고 users 행도 만들지 않는다")
    void createAccount_nicknameAlreadyOccupied_throwsDuplicateNicknameWithoutSaving() {
        // given
        OauthTicket ticket = signupTicket("fresh@example.com", true);
        given(userRepository.existsByEmail("fresh@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("중복닉네임")).willReturn(true);
        OauthAccountWriter writer = newWriter();

        // when & then
        assertThatThrownBy(() -> writer.createAccount(ticket, "중복닉네임"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-OAU-30, 31, 32, 35, 86] 정상 가입이면 users·users_account·users_bq·연동 행을 각 1건씩 "
            + "만든다 — 계정 이메일은 티켓의 이메일로 고정되고, 연동은 티켓의 provider·식별자로 만들어지며, "
            + "bqScore는 0이다")
    void createAccount_success_createsAllFourRowsWithTicketBoundValues() {
        // given
        OauthTicket ticket = signupTicket("fresh@example.com", true);
        given(userRepository.existsByEmail("fresh@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("승리요정")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(inv -> inv.getArgument(0));
        given(authService.issueTokens(any(UserAccount.class), any()))
                .willReturn(new TokenResponse("access", "refresh"));
        OauthAccountWriter writer = newWriter();

        // when
        TokenResponse response = writer.createAccount(ticket, "승리요정");

        // then
        assertThat(response.accessToken()).isEqualTo("access");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("fresh@example.com");
        assertThat(userCaptor.getValue().isEmailVerified()).isTrue();

        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        UserAccount savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getNickname()).isEqualTo("승리요정");
        assertThat(savedAccount.getPassword()).isEqualTo(SocialAccountPolicy.LOCKED_PASSWORD);

        ArgumentCaptor<UserBq> bqCaptor = ArgumentCaptor.forClass(UserBq.class);
        verify(userBqRepository).save(bqCaptor.capture());
        assertThat(bqCaptor.getValue().getUserAccount()).isSameAs(savedAccount);
        assertThat(bqCaptor.getValue().getBqScore()).isZero();

        ArgumentCaptor<UserOauthLink> linkCaptor = ArgumentCaptor.forClass(UserOauthLink.class);
        verify(userOauthLinkRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getUserAccount()).isSameAs(savedAccount);
        assertThat(linkCaptor.getValue().getProvider()).isEqualTo(OauthProvider.GOOGLE);
        assertThat(linkCaptor.getValue().getProviderUserId()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("소셜 가입도 자체 가입과 똑같이 기본 캐릭터·기본 의상을 지급한다 — 빠뜨리면 소셜 가입자만 "
            + "캐릭터가 없다")
    void createAccount_success_grantsDefaultCharacter() {
        // given
        OauthTicket ticket = signupTicket("fresh@example.com", true);
        given(userRepository.existsByEmail("fresh@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("승리요정")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(inv -> inv.getArgument(0));
        given(authService.issueTokens(any(UserAccount.class), any()))
                .willReturn(new TokenResponse("access", "refresh"));
        OauthAccountWriter writer = newWriter();

        // when
        writer.createAccount(ticket, "승리요정");

        // then: 지급 대상이 방금 저장된 그 계정이어야 한다.
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(accountCaptor.capture());
        verify(defaultCharacterGrantService).grantDefaults(accountCaptor.getValue());
    }

    @Test
    @DisplayName("닉네임 중복으로 소셜 가입이 거절되면 기본 캐릭터 지급도 일어나지 않는다")
    void createAccount_duplicateNickname_doesNotGrantDefaultCharacter() {
        // given
        OauthTicket ticket = signupTicket("fresh@example.com", true);
        given(userRepository.existsByEmail("fresh@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("승리요정")).willReturn(true);
        OauthAccountWriter writer = newWriter();

        // when & then
        assertThatThrownBy(() -> writer.createAccount(ticket, "승리요정"))
                .isInstanceOf(BusinessException.class);

        verify(defaultCharacterGrantService, never()).grantDefaults(any());
    }

    @Test
    @DisplayName("[USER-OAU-84] 미검증 이메일로 발급된 티켓(provider 미검증 이메일)으로 가입하면 계정의 "
            + "emailVerified가 false로 저장된다")
    void createAccount_unverifiedTicketEmail_savesEmailVerifiedFalse() {
        // given
        OauthTicket ticket = signupTicket("unverified@example.com", false);
        given(userRepository.existsByEmail("unverified@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("승리요정")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(inv -> inv.getArgument(0));
        given(authService.issueTokens(any(UserAccount.class), any()))
                .willReturn(new TokenResponse("a", "r"));
        OauthAccountWriter writer = newWriter();

        // when
        writer.createAccount(ticket, "승리요정");

        // then
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("[USER-OAU-33] 성공 시 토큰 쌍을 반환한다(자체 가입이 Boolean을 주는 것과 다르다)")
    void createAccount_success_returnsTokenPair() {
        // given
        OauthTicket ticket = signupTicket("tokens@example.com", true);
        given(userRepository.existsByEmail("tokens@example.com")).willReturn(false);
        given(userAccountRepository.existsByNickname("승리요정")).willReturn(false);
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(userAccountRepository.save(any(UserAccount.class))).willAnswer(inv -> inv.getArgument(0));
        given(authService.issueTokens(any(UserAccount.class), any()))
                .willReturn(new TokenResponse("tok-access", "tok-refresh"));
        OauthAccountWriter writer = newWriter();

        // when
        TokenResponse response = writer.createAccount(ticket, "승리요정");

        // then
        assertThat(response).isEqualTo(new TokenResponse("tok-access", "tok-refresh"));
    }
}
