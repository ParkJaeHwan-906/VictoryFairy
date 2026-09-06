package com.skhynix.user.oauth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.oauth.dto.OauthAuthResponse;
import com.skhynix.user.oauth.service.OauthAuthService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /auth/oauth/{provider}}·{@code /link/send-code}·{@code /link/verify}·{@code /signup}
 * — 소셜 로그인 4경로가 요구사항 {@code docs/requirements/user/oauth-login.md}대로 배선됐는지 검증한다.
 * 슬라이스 구성(컨텍스트 자동 병합 우회·{@code SecurityConfig}/{@code UserAccountRepository} 목 필요)은
 * {@code AuthControllerEmailVerificationTest}와 동일한 패턴을 따른다. {@link OauthAuthService}는
 * 전부 목으로 대체해 이 레이어의 관심사(요청 검증·상태코드·에러코드 매핑·permitAll)만 본다 — 신원 해석·
 * 인증번호 정책은 서비스 계층 테스트 소관이다.
 */
@WebMvcTest(OauthController.class)
@ContextConfiguration(classes = OauthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class OauthControllerTest {

    private static final String VALIDATION_MESSAGE = "입력값이 올바르지 않습니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OauthAuthService oauthAuthService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    // ---------- POST /auth/oauth/{provider} ----------

    @Test
    @DisplayName("[USER-OAU-3] Authorization 헤더 없이 호출해도 401이 아니다(소셜 인증 경로는 인증 없이 처리된다)")
    void authenticate_withoutAuthorizationHeader_notUnauthorized() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("google"), any()))
                .willReturn(OauthAuthResponse.login(new TokenResponse("access", "refresh")));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/google"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[USER-OAU-6] 인가코드가 빈값이면 400을 반환하고 서비스는 호출되지 않는다")
    void authenticate_blankCode_returns400WithoutCallingService() throws Exception {
        // given
        String json = """
                {"code":"","redirectUri":"https://victoryfairy.com/oauth/google"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(VALIDATION_MESSAGE));

        verifyNoInteractions(oauthAuthService);
    }

    @Test
    @DisplayName("[USER-OAU-11] LOGIN 응답은 200과 status:LOGIN + 토큰 쌍을 그대로 내려보낸다")
    void authenticate_loginStatus_returns200WithTokens() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("kakao"), any()))
                .willReturn(OauthAuthResponse.login(new TokenResponse("access-1", "refresh-1")));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/kakao"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGIN"))
                .andExpect(jsonPath("$.accessToken").value("access-1"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-1"))
                .andExpect(jsonPath("$.ticket").doesNotExist());
    }

    @Test
    @DisplayName("[USER-OAU-12, 27] SIGNUP_REQUIRED 응답은 200과 status:SIGNUP_REQUIRED + ticket + email을 "
            + "반환한다(4xx가 아니다)")
    void authenticate_signupRequiredStatus_returns200WithTicketAndEmail() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("google"), any()))
                .willReturn(OauthAuthResponse.signupRequired("signup-ticket", "new@example.com"));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/google"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.ticket").value("signup-ticket"))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    @DisplayName("[USER-OAU-68] EMAIL_VERIFICATION_REQUIRED 응답도 200이다")
    void authenticate_emailVerificationRequiredStatus_returns200() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("naver"), any()))
                .willReturn(OauthAuthResponse.emailVerificationRequired("link-ticket", "existing@example.com"));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/naver"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/naver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMAIL_VERIFICATION_REQUIRED"))
                .andExpect(jsonPath("$.ticket").value("link-ticket"));
    }

    @Test
    @DisplayName("[USER-OAU-90] EMAIL_INPUT_REQUIRED 응답도 200이다(4값 전부 200 — USER-OAU-10)")
    void authenticate_emailInputRequiredStatus_returns200() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("kakao"), any()))
                .willReturn(OauthAuthResponse.emailInputRequired("input-ticket"));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/kakao"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMAIL_INPUT_REQUIRED"))
                .andExpect(jsonPath("$.ticket").value("input-ticket"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("[USER-OAU-5] 서비스가 UNSUPPORTED_OAUTH_PROVIDER를 던지면 400과 안내 메시지를 반환한다")
    void authenticate_unsupportedProvider_returns400WithMessage() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("apple"), any()))
                .willThrow(new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/apple"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-64] 서비스가 INVALID_OAUTH_REDIRECT_URI를 던지면 400과 안내 메시지를 반환한다")
    void authenticate_invalidRedirectUri_returns400WithMessage() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("google"), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_REDIRECT_URI));
        String json = """
                {"code":"auth-code","redirectUri":"https://evil.example.com"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_OAUTH_REDIRECT_URI.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-7] 서비스가 INVALID_OAUTH_CODE를 던지면 401과 안내 메시지를 반환한다")
    void authenticate_invalidCode_returns401WithMessage() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("google"), any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_CODE));
        String json = """
                {"code":"expired-code","redirectUri":"https://victoryfairy.com/oauth/google"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_OAUTH_CODE.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-8] 서비스가 OAUTH_PROVIDER_UNAVAILABLE을 던지면 502와 안내 메시지를 반환한다"
            + "(이 저장소 최초의 502)")
    void authenticate_providerUnavailable_returns502WithMessage() throws Exception {
        // given
        given(oauthAuthService.authenticate(eq("google"), any()))
                .willThrow(new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE));
        String json = """
                {"code":"auth-code","redirectUri":"https://victoryfairy.com/oauth/google"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.message").value(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE.getMessage()));
    }

    // ---------- POST /auth/oauth/link/send-code ----------

    @Test
    @DisplayName("[USER-OAU-69] 유효한 요청이면 200을 반환하고 티켓·이메일을 그대로 서비스에 위임한다")
    void sendLinkCode_validRequest_returns200AndDelegates() throws Exception {
        // given
        String json = """
                {"ticket":"link-token","email":null}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(oauthAuthService).sendLinkCode(any());
    }

    @Test
    @DisplayName("ticket이 빈값이면 400을 반환하고 서비스는 호출되지 않는다")
    void sendLinkCode_blankTicket_returns400WithoutCallingService() throws Exception {
        // given
        String json = """
                {"ticket":""}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oauthAuthService);
    }

    @Test
    @DisplayName("[USER-OAU-79, 94] 서비스가 EMAIL_SEND_COOLDOWN을 던지면 429를 반환한다")
    void sendLinkCode_cooldown_returns429() throws Exception {
        // given
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.EMAIL_SEND_COOLDOWN))
                .when(oauthAuthService).sendLinkCode(any());
        String json = """
                {"ticket":"link-token"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.message").value(ErrorCode.EMAIL_SEND_COOLDOWN.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-76] 서비스가 INVALID_OAUTH_TICKET을 던지면 400을 반환한다")
    void sendLinkCode_invalidTicket_returns400() throws Exception {
        // given
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TICKET))
                .when(oauthAuthService).sendLinkCode(any());
        String json = """
                {"ticket":"unknown-token"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_OAUTH_TICKET.getMessage()));
    }

    // ---------- POST /auth/oauth/link/verify ----------

    @Test
    @DisplayName("인증 통과 응답도 최초 인증과 같은 형태(status/accessToken/...)로 200을 반환한다")
    void verifyLinkCode_success_returns200WithSameShapeAsAuthenticate() throws Exception {
        // given
        given(oauthAuthService.verifyLinkCode(any()))
                .willReturn(OauthAuthResponse.login(new TokenResponse("acc", "ref")));
        String json = """
                {"ticket":"link-token","code":"123456"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOGIN"))
                .andExpect(jsonPath("$.accessToken").value("acc"));
    }

    @Test
    @DisplayName("ticket·code가 모두 빈값이면 400을 반환하고 서비스는 호출되지 않는다")
    void verifyLinkCode_blankTicketAndCode_returns400WithoutCallingService() throws Exception {
        // given
        String json = """
                {"ticket":"","code":""}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oauthAuthService);
    }

    @Test
    @DisplayName("[USER-OAU-77] 서비스가 INVALID_VERIFICATION_CODE를 던지면 400을 반환한다")
    void verifyLinkCode_wrongCode_returns400() throws Exception {
        // given
        given(oauthAuthService.verifyLinkCode(any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE));
        String json = """
                {"ticket":"link-token","code":"999999"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_VERIFICATION_CODE.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-78] 서비스가 VERIFICATION_ATTEMPTS_EXCEEDED를 던지면 400을 반환한다")
    void verifyLinkCode_attemptsExceeded_returns400() throws Exception {
        // given
        given(oauthAuthService.verifyLinkCode(any()))
                .willThrow(new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED));
        String json = """
                {"ticket":"link-token","code":"123456"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/link/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED.getMessage()));
    }

    // ---------- POST /auth/oauth/signup ----------

    @Test
    @DisplayName("[USER-OAU-33] 가입 성공 시 201과 토큰 쌍을 반환한다(자체 가입의 Boolean 응답과 다르다)")
    void signup_success_returns201WithTokenPair() throws Exception {
        // given
        given(oauthAuthService.signup(any())).willReturn(new TokenResponse("access", "refresh"));
        String json = """
                {"ticket":"signup-token","nickname":"승리요정"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    @DisplayName("[USER-OAU-36] ticket이 빈값이면 400을 반환하고 서비스는 호출되지 않는다")
    void signup_blankTicket_returns400WithoutCallingService() throws Exception {
        // given
        String json = """
                {"ticket":"","nickname":"승리요정"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(oauthAuthService);
    }

    @Test
    @DisplayName("[USER-OAU-37] nickname이 닉네임 정책을 위반하면 400을 반환하고 서비스는 호출되지 않는다"
            + "(자체 가입과 같은 @ValidNickname을 공유해 메시지가 문자 그대로 같다)")
    void signup_invalidNickname_returns400WithoutCallingService() throws Exception {
        // given: 11자 닉네임(길이 위반)
        String json = """
                {"ticket":"signup-token","nickname":"가나다라마바사아자차카"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.nickname").exists());

        verifyNoInteractions(oauthAuthService);
    }

    @Test
    @DisplayName("[USER-OAU-38] 서비스가 DUPLICATE_NICKNAME을 던지면 409를 반환한다")
    void signup_duplicateNickname_returns409() throws Exception {
        // given
        given(oauthAuthService.signup(any()))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME));
        String json = """
                {"ticket":"signup-token","nickname":"이미존재"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_NICKNAME.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-40] 서비스가 DUPLICATE_EMAIL을 던지면 409를 반환한다")
    void signup_duplicateEmail_returns409() throws Exception {
        // given
        given(oauthAuthService.signup(any()))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));
        String json = """
                {"ticket":"signup-token","nickname":"승리요정"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_EMAIL.getMessage()));
    }

    @Test
    @DisplayName("[USER-OAU-36] 서비스가 INVALID_OAUTH_TICKET을 던지면 400을 반환한다")
    void signup_invalidTicket_returns400() throws Exception {
        // given
        given(oauthAuthService.signup(any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_OAUTH_TICKET));
        String json = """
                {"ticket":"expired-token","nickname":"승리요정"}
                """;

        // when & then
        mockMvc.perform(post("/auth/oauth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_OAUTH_TICKET.getMessage()));
    }
}
