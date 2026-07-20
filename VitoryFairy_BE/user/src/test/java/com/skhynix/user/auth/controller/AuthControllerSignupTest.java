package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.global.error.GlobalExceptionHandler;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/auth/signup}의 {@link SignupRequest#password()} 검증 정책이
 * 서블릿 레이어(@Valid + GlobalExceptionHandler)까지 실제로 적용되는지 확인한다.
 *
 * <p><b>보안 설정 관련 결정</b>: 이 슬라이스는 {@code @WithMockUser}로 인증을 우회하는 대신
 * 운영 {@link SecurityConfig}를 {@code @Import}해 실제 {@code permitAll} 규칙(/api/auth/**)이
 * 그대로 적용되는지까지 함께 검증한다. {@link JwtTokenProvider}는 요청에 토큰이 없으므로
 * 실제 호출되지 않지만, {@code SecurityFilterChain} 빈 구성에 필요해 {@code @MockitoBean}으로
 * 대체했다. {@code SecurityConfig#securityFilterChain}이 {@code UserAccountRepository}도
 * 파라미터로 받게 되면서(uid → id 조회) 같은 이유로 {@code @MockitoBean}으로 추가했다.
 *
 * <p><b>{@code @ContextConfiguration(classes = AuthController.class)}를 추가한 이유</b>:
 * {@code UserApplication}이 {@code @EnableJpaRepositories(basePackages = "com.skhynix")}를
 * 직접 들고 있어, {@code @WebMvcTest}가 기본 동작대로 가장 가까운 {@code @SpringBootConfiguration}
 * (UserApplication)을 자동으로 병합하면 슬라이스에 없는 {@code entityManagerFactory} 빈을 요구하는
 * 리포지토리 빈들이 등록되며 컨텍스트 로딩이 실패한다({@code NoSuchBeanDefinitionException:
 * entityManagerFactory}). 테스트 클래스에 {@code @ContextConfiguration(classes = ...)}로
 * (@TestConfiguration이 아닌) 클래스를 명시하면 Spring Boot 테스트 부트스트래퍼가 이 자동 병합을
 * 건너뛰므로({@code SpringBootTestContextBootstrapper#containsNonTestComponent}), UserApplication의
 * 컴포넌트 스캔·JPA 설정을 전혀 거치지 않고 컨트롤러 슬라이스만 순수하게 띄울 수 있다. 이 경우 패키지
 * 스캔이 발생하지 않으므로 필요한 협력 빈({@code GlobalExceptionHandler}, {@code SecurityConfig})은
 * {@code @Import}로 명시했다. 프로덕션 코드({@code UserApplication})는 건드리지 않았다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerSignupTest {

    private static final String SIZE_MESSAGE = "비밀번호는 8~12자여야 합니다.";
    private static final String PATTERN_MESSAGE = "비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다.";

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

    private SignupRequest requestWithPassword(String password) {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, "nickname", password);
    }

    @Test
    @DisplayName("정책을 만족하는 비밀번호로 회원가입하면 201을 반환하고 AuthService.signup()이 호출된다")
    void signup_validPassword_returns201AndCallsService() throws Exception {
        // given
        given(authService.signup(any())).willReturn(1L);
        String json = objectMapper.writeValueAsString(requestWithPassword("abc123!@"));

        // when & then
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(content().string("true"));

        verify(authService).signup(any());
    }

    private static Stream<Arguments> invalidPasswords() {
        return Stream.of(
                // description, password, 유발되는 제약의 메시지
                Arguments.of("길이 미달(7자, 구성은 충족)", "ab1!234", SIZE_MESSAGE),
                Arguments.of("길이 초과(13자, 구성은 충족)", "abc123!@#$%^&", SIZE_MESSAGE),
                Arguments.of("영문 없음", "12345678!", PATTERN_MESSAGE),
                Arguments.of("숫자 없음", "abcdefg!", PATTERN_MESSAGE),
                Arguments.of("특수문자 없음", "abc12345", PATTERN_MESSAGE),
                Arguments.of("공백은 특수문자로 인정되지 않음", "abc12345 ", PATTERN_MESSAGE),
                Arguments.of("null은 길이 위반으로 처리(@NotBlank 없이 @ValidPassword 단독 책임)", null, SIZE_MESSAGE),
                Arguments.of("빈 문자열은 길이 위반으로 처리(@NotBlank 없이 @ValidPassword 단독 책임)", "", SIZE_MESSAGE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} -> 400 (password=\"{1}\")")
    @MethodSource("invalidPasswords")
    @DisplayName("비밀번호 정책을 위반하면 400과 위반 메시지를 반환하고, AuthService는 호출되지 않는다")
    void signup_invalidPassword_returns400WithoutCallingService(
            String description, String password, String expectedMessage) throws Exception {
        // given
        String json = objectMapper.writeValueAsString(requestWithPassword(password));

        // when & then
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.password").value(expectedMessage));

        verifyNoInteractions(authService);
    }
}
