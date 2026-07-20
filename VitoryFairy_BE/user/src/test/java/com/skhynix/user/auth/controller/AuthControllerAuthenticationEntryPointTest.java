package com.skhynix.user.auth.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.PasswordValidationRequest;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.global.error.GlobalExceptionHandler;
import com.skhynix.user.global.error.RestAuthenticationEntryPoint;
import com.skhynix.user.global.jwt.JwtTokenProvider;
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
 * 미인증 요청이 403이 아니라 401 + {@code ApiResponse} 바디로 응답하는지, 그리고 이 401이
 * {@code /api/auth/login} 자격 실패의 401(둘 다 {@link GlobalExceptionHandler}가 아닌 서로 다른 경로에서
 * 나온다)과 메시지로 구분되는지를 검증한다.
 *
 * <p><b>배경</b>: {@code SecurityConfig}가 {@code formLogin}/{@code httpBasic}을 모두 disable하는데
 * 이 둘이 바로 {@code AuthenticationEntryPoint}를 등록하는 주체라, 명시하지 않으면 Spring Security
 * 기본값({@code Http403ForbiddenEntryPoint})으로 떨어져 미인증 요청이 403이 됐었다.
 * {@link RestAuthenticationEntryPoint}를 {@code exceptionHandling}에 명시로 등록해 401로 고정했다.
 *
 * <p><b>구분 포인트</b>: {@link RestAuthenticationEntryPoint}가 만드는 401은
 * {@code ExceptionTranslationFilter}(서블릿 필터 단계, {@code DispatcherServlet} 바깥)에서 나오고
 * 메시지는 {@code ErrorCode.UNAUTHENTICATED}("인증이 필요합니다.")다. 반면 로그인 자격 실패의 401은
 * {@code AuthService.login()}이 던진 {@code BusinessException(INVALID_CREDENTIALS)}를
 * {@link GlobalExceptionHandler}가 컨트롤러 단계에서 잡아 만든 것이고 메시지는
 * "이메일 또는 비밀번호가 올바르지 않습니다."다. 상태 코드만으로는 두 경로가 구분되지 않으므로
 * 엔트리포인트가 정상 인증 실패 응답을 가로채 메시지를 덮어쓰지 않는지가 이 테스트의 핵심이다.
 *
 * <p>슬라이스 구성(컨텍스트 자동 병합 우회, {@code UserAccountRepository}를 {@code @MockitoBean}으로
 * 대체하는 이유)은 {@code AuthControllerSignupTest}의 Javadoc과 동일하다. {@code ObjectMapper}는
 * {@code SecurityConfig#securityFilterChain}이 {@code RestAuthenticationEntryPoint} 생성에 필요로
 * 하는 빈인데, 별도 {@code @MockitoBean} 없이도 웹 슬라이스의 Jackson 자동 설정으로 주입된다
 * (spring-dev 실측: 슬라이스 컨텍스트에 Jackson 자동 설정이 포함돼 있음을 이 테스트가 그린으로
 * 통과함으로써 재확인한다).
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerAuthenticationEntryPointTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final String INVALID_CREDENTIALS_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @Test
    @DisplayName("토큰 없이 인증이 필요한 경로에 접근하면 403이 아니라 401과 ApiResponse 바디(success:false, "
            + "message:\"인증이 필요합니다.\")를 반환한다")
    void unauthenticatedRequest_toProtectedPath_returns401WithApiResponseBody() throws Exception {
        // AuthController가 매핑하는 경로는 전부 permitAll(/api/auth/**)이라, 슬라이스 안에서
        // anyRequest().authenticated() 규칙을 태우려면 컨트롤러가 없는 경로를 찔러야 한다.
        // AuthorizationFilter가 DispatcherServlet 핸들러 매핑보다 먼저 요청을 가로막으므로
        // 매핑되지 않은 경로라도 404가 아니라 엔트리포인트의 401이 그대로 응답된다.
        mockMvc.perform(get("/api/nonexistent-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));
    }

    @Test
    @DisplayName("형식이 깨진 Bearer 토큰으로 인증이 필요한 경로에 접근해도 401과 동일한 ApiResponse 바디를 반환한다")
    void garbageBearerToken_toProtectedPath_returns401WithSameBody() throws Exception {
        mockMvc.perform(get("/api/nonexistent-probe")
                        .header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));
    }

    @Test
    @DisplayName("permitAll 경로(POST /api/auth/password/validate)는 토큰이 없어도 401로 새지 않는다")
    void permitAllPath_withoutToken_doesNotLeakInto401() throws Exception {
        String json = objectMapper.writeValueAsString(new PasswordValidationRequest("abc123!@"));

        mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("로그인 자격 실패의 401은 엔트리포인트가 아니라 GlobalExceptionHandler가 낸 것이라 메시지가 다르다")
    void loginWithWrongCredentials_returns401ButWithDifferentMessageThanEntryPoint() throws Exception {
        // given
        given(authService.login(org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        String json = objectMapper.writeValueAsString(new LoginRequest("test@example.com", "wrongPassword1!"));

        // when & then: 상태 코드는 위 미인증 케이스와 같은 401이지만 메시지가 다르다
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS_MESSAGE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(UNAUTHENTICATED_MESSAGE)));
    }
}
