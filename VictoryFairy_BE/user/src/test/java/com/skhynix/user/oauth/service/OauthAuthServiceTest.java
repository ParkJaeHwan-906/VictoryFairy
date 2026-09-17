package com.skhynix.user.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.OauthProvider;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.oauth.client.OauthClient;
import com.skhynix.user.oauth.client.OauthClientRegistry;
import com.skhynix.user.oauth.client.OauthUserInfo;
import com.skhynix.user.oauth.dto.OauthAuthResponse;
import com.skhynix.user.oauth.dto.OauthLinkSendCodeRequest;
import com.skhynix.user.oauth.dto.OauthLinkVerifyRequest;
import com.skhynix.user.oauth.dto.OauthLoginRequest;
import com.skhynix.user.oauth.dto.OauthSignupRequest;
import com.skhynix.user.oauth.store.OauthTicket;
import com.skhynix.user.oauth.store.OauthTicketStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link OauthAuthService} — 소셜 로그인 4경로의 지휘 로직(provider 호출 순서·티켓 종류 대조·소비 시점)만
 * 다룬다. 신원 해석 자체는 {@link OauthIdentityResolverTest} 소관이고, 여기서는
 * {@link OauthIdentityResolver}·{@link OauthEmailVerificationService}·{@link OauthAccountWriter}를
 * 전부 목으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class OauthAuthServiceTest {

    @Mock
    private OauthClientRegistry clientRegistry;

    @Mock
    private OauthTicketStore ticketStore;

    @Mock
    private OauthIdentityResolver identityResolver;

    @Mock
    private OauthEmailVerificationService emailVerificationService;

    @Mock
    private OauthAccountWriter accountWriter;

    @Mock
    private OauthClient client;

    private OauthAuthService newService() {
        return new OauthAuthService(clientRegistry, ticketStore, identityResolver,
                emailVerificationService, accountWriter);
    }

    // ---------- authenticate() ----------

    @Test
    @DisplayName("[USER-OAU-64] redirectUri가 허용 목록에 없으면 400을 던지고 provider는 호출되지 않는다")
    void authenticate_disallowedRedirectUri_throwsAndNeverCallsProvider() {
        // given
        OauthLoginRequest request = new OauthLoginRequest("auth-code", "https://evil.example.com");
        given(clientRegistry.get("google")).willReturn(client);
        given(client.allowsRedirectUri("https://evil.example.com")).willReturn(false);
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.authenticate("google", request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_REDIRECT_URI);

        verify(client, never()).authenticate(anyString(), anyString());
        verifyNoInteractions(identityResolver);
    }

    @Test
    @DisplayName("[USER-OAU-1, 66] redirectUri가 허용되면 provider 토큰 교환을 요청 본문의 redirectUri "
            + "그대로 실어 1회 호출하고, 그 결과를 신원 해석에 넘긴다")
    void authenticate_allowedRedirectUri_callsProviderOnceWithSameRedirectUriAndDelegatesToResolver() {
        // given
        OauthLoginRequest request = new OauthLoginRequest("auth-code", "victoryfairy://oauth/google");
        OauthUserInfo userInfo = new OauthUserInfo("sub-1", "user@example.com", true);
        OauthAuthResponse expected = OauthAuthResponse.login(new TokenResponse("access", "refresh"));
        given(clientRegistry.get("google")).willReturn(client);
        given(client.allowsRedirectUri("victoryfairy://oauth/google")).willReturn(true);
        given(client.authenticate("auth-code", "victoryfairy://oauth/google")).willReturn(userInfo);
        given(client.provider()).willReturn(OauthProvider.GOOGLE);
        given(identityResolver.resolve(OauthProvider.GOOGLE, userInfo)).willReturn(expected);
        OauthAuthService service = newService();

        // when
        OauthAuthResponse response = service.authenticate("google", request);

        // then
        assertThat(response).isSameAs(expected);
        verify(client, org.mockito.Mockito.times(1))
                .authenticate("auth-code", "victoryfairy://oauth/google");
    }

    @Test
    @DisplayName("[USER-OAU-5] 지원하지 않는 provider면 레지스트리가 던진 예외가 그대로 전파된다")
    void authenticate_unsupportedProvider_propagatesRegistryException() {
        // given
        given(clientRegistry.get("apple"))
                .willThrow(new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.authenticate("apple",
                new OauthLoginRequest("code", "https://victoryfairy.com")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER);
    }

    // ---------- sendLinkCode() ----------

    @Test
    @DisplayName("[USER-OAU-69, 92] 유효한 LINK/INPUT 티켓이면 티켓 원문과 요청 이메일을 그대로 인증 서비스에 위임한다")
    void sendLinkCode_validTicket_delegatesToEmailVerificationService() {
        // given
        OauthTicket ticket = OauthTicket.link(OauthProvider.KAKAO, "kakao-1", "linked@example.com");
        given(ticketStore.find("link-token")).willReturn(Optional.of(ticket));
        OauthAuthService service = newService();

        // when
        service.sendLinkCode(new OauthLinkSendCodeRequest("link-token", null));

        // then
        verify(emailVerificationService).sendCode("link-token", ticket, null);
    }

    @Test
    @DisplayName("[USER-OAU-76] 존재하지 않는 티켓으로 발송을 요청하면 400 INVALID_OAUTH_TICKET을 던지고 "
            + "인증 서비스는 호출되지 않는다")
    void sendLinkCode_missingTicket_throwsInvalidTicketWithoutDelegating() {
        // given
        given(ticketStore.find("unknown-token")).willReturn(Optional.empty());
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.sendLinkCode(new OauthLinkSendCodeRequest("unknown-token", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_TICKET);

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("SIGNUP 티켓으로 발송을 요청하면(단계가 다른 티켓) 400 INVALID_OAUTH_TICKET을 던진다 — "
            + "어느 단계의 티켓이 살아 있는지 탐색으로 알아낼 수 없다")
    void sendLinkCode_signupTicket_throwsInvalidTicket() {
        // given
        OauthTicket signupTicket = OauthTicket.signup(OauthProvider.GOOGLE, "sub", "a@b.com", true);
        given(ticketStore.find("signup-token")).willReturn(Optional.of(signupTicket));
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.sendLinkCode(new OauthLinkSendCodeRequest("signup-token", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_TICKET);

        verifyNoInteractions(emailVerificationService);
    }

    // ---------- verifyLinkCode() ----------

    @Test
    @DisplayName("유효한 티켓과 코드로 인증을 요청하면 증명된 이메일로 신원 해석에 위임한다")
    void verifyLinkCode_validTicket_delegatesToResolverWithProvenEmail() {
        // given
        OauthTicket ticket = OauthTicket.input(OauthProvider.KAKAO, "kakao-2");
        given(ticketStore.find("input-token")).willReturn(Optional.of(ticket));
        given(emailVerificationService.verifyAndConsume("input-token", "123456"))
                .willReturn("proven@example.com");
        OauthAuthResponse expected = OauthAuthResponse.signupRequired("new-ticket", "proven@example.com");
        given(identityResolver.resolveWithProvenEmail(OauthProvider.KAKAO, "kakao-2", "proven@example.com"))
                .willReturn(expected);
        OauthAuthService service = newService();

        // when
        OauthAuthResponse response = service.verifyLinkCode(new OauthLinkVerifyRequest("input-token", "123456"));

        // then
        assertThat(response).isSameAs(expected);
    }

    @Test
    @DisplayName("SIGNUP 티켓으로 인증을 요청하면 400 INVALID_OAUTH_TICKET을 던지고 인증 서비스는 호출되지 않는다")
    void verifyLinkCode_signupTicket_throwsInvalidTicketWithoutVerifying() {
        // given
        OauthTicket signupTicket = OauthTicket.signup(OauthProvider.NAVER, "sub", "a@b.com", true);
        given(ticketStore.find("signup-token")).willReturn(Optional.of(signupTicket));
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.verifyLinkCode(new OauthLinkVerifyRequest("signup-token", "123456")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_TICKET);

        verifyNoInteractions(emailVerificationService);
        verifyNoInteractions(identityResolver);
    }

    // ---------- signup() ----------

    @Test
    @DisplayName("[USER-OAU-30, 33, 34] 유효한 SIGNUP 티켓으로 가입하면 계정을 먼저 만들고 그 다음에 티켓을 "
            + "소비한다(순서 고정 — 앞에 두면 닉네임 중복으로 거절된 요청이 재시도할 티켓을 잃는다)")
    void signup_validTicket_createsAccountBeforeConsumingTicket() {
        // given
        OauthTicket ticket = OauthTicket.signup(OauthProvider.GOOGLE, "sub-1", "new@example.com", true);
        given(ticketStore.find("signup-token")).willReturn(Optional.of(ticket));
        TokenResponse tokens = new TokenResponse("access", "refresh");
        given(accountWriter.createAccount(ticket, "승리요정")).willReturn(tokens);
        OauthAuthService service = newService();

        // when
        TokenResponse response = service.signup(new OauthSignupRequest("signup-token", "승리요정"));

        // then
        assertThat(response).isSameAs(tokens);
        InOrder order = inOrder(accountWriter, ticketStore);
        order.verify(accountWriter).createAccount(ticket, "승리요정");
        order.verify(ticketStore).consume("signup-token");
    }

    @Test
    @DisplayName("[USER-OAU-34, 재시도 보장] 닉네임 중복 등으로 계정 생성이 실패하면 티켓은 소비되지 않는다"
            + "(사용자가 다른 닉네임으로 같은 티켓을 재시도할 수 있어야 한다)")
    void signup_accountCreationFails_doesNotConsumeTicket() {
        // given
        OauthTicket ticket = OauthTicket.signup(OauthProvider.GOOGLE, "sub-1", "new@example.com", true);
        given(ticketStore.find("signup-token")).willReturn(Optional.of(ticket));
        given(accountWriter.createAccount(ticket, "중복닉네임"))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME));
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.signup(new OauthSignupRequest("signup-token", "중복닉네임")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_NICKNAME);

        verify(ticketStore, never()).consume(anyString());
    }

    @Test
    @DisplayName("[USER-OAU-36] 가입 티켓이 존재하지 않으면 400 INVALID_OAUTH_TICKET을 던지고 계정 생성은 "
            + "시도되지 않는다")
    void signup_missingTicket_throwsInvalidTicketWithoutCreatingAccount() {
        // given
        given(ticketStore.find("unknown-token")).willReturn(Optional.empty());
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.signup(new OauthSignupRequest("unknown-token", "승리요정")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_TICKET);

        verifyNoInteractions(accountWriter);
    }

    @Test
    @DisplayName("LINK 티켓으로 가입을 요청하면(단계가 다른 티켓) 400 INVALID_OAUTH_TICKET을 던진다")
    void signup_linkTicket_throwsInvalidTicket() {
        // given
        OauthTicket linkTicket = OauthTicket.link(OauthProvider.NAVER, "naver-1", "a@b.com");
        given(ticketStore.find("link-token")).willReturn(Optional.of(linkTicket));
        OauthAuthService service = newService();

        // when & then
        assertThatThrownBy(() -> service.signup(new OauthSignupRequest("link-token", "승리요정")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_OAUTH_TICKET);

        verifyNoInteractions(accountWriter);
    }
}
