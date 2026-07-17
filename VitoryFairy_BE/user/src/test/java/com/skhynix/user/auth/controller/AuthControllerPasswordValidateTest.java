package com.skhynix.user.auth.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.entity.Gender;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.PasswordValidationRequest;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.service.AuthService;
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
 * {@code POST /api/auth/password/validate}가 계약대로 동작하는지 확인한다: 정책 위반이어도 항상
 * HTTP 200을 반환하고({@code valid:false} + 메시지 1개), DB를 건드리지 않으며(AuthService 미호출),
 * {@code SignupRequest}의 Bean Validation 결과와 판정이 어긋나지 않는지까지 검증한다.
 *
 * <p>슬라이스 구성은 {@code AuthControllerSignupTest}와 동일한 패턴을 따른다. 특히
 * {@code @ContextConfiguration(classes = AuthController.class)}로 {@code UserApplication}의
 * {@code @EnableJpaRepositories} 자동 병합을 우회하는 이유와, {@code UserAccountRepository}를 {@code @MockitoBean}으로
 * 대체하는 이유(SecurityConfig의 securityFilterChain 빈 구성에 필요)는 해당 클래스의 Javadoc을 참고.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerPasswordValidateTest {

    private static final String LENGTH_MESSAGE = "비밀번호는 8~12자여야 합니다.";
    private static final String PATTERN_MESSAGE = "비밀번호는 영문, 숫자, 특수문자(!@#$%^&* 등)를 각각 1자 이상 포함해야 합니다.";
    private static final String VALID_MESSAGE = "사용 가능한 비밀번호입니다.";

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

    private String validatePasswordJson(String password) throws Exception {
        return objectMapper.writeValueAsString(new PasswordValidationRequest(password));
    }

    private SignupRequest signupRequestWithPassword(String password) {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, "nickname", password);
    }

    @Test
    @DisplayName("정책을 만족하는 비밀번호는 200과 valid:true, 통과 메시지를 반환한다")
    void validate_validPassword_returns200WithValidTrue() throws Exception {
        // given
        String json = validatePasswordJson("abc123!@");

        // when & then
        mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.message").value(VALID_MESSAGE));

        verifyNoInteractions(authService);
    }

    private static Stream<Arguments> invalidPasswords() {
        return Stream.of(
                // description, password, 유발되는 규칙의 메시지
                Arguments.of("길이 미달(7자, 구성은 충족)", "ab1!234", LENGTH_MESSAGE),
                Arguments.of("길이 초과(13자, 구성은 충족)", "abc123!@#$%^&", LENGTH_MESSAGE),
                Arguments.of("영문 없음", "12345678!", PATTERN_MESSAGE),
                Arguments.of("숫자 없음", "abcdefg!", PATTERN_MESSAGE),
                Arguments.of("특수문자 없음", "abc12345", PATTERN_MESSAGE),
                Arguments.of("공백은 특수문자로 인정되지 않음", "abc12345 ", PATTERN_MESSAGE),
                Arguments.of("길이·구성 동시 위반 시 길이 메시지가 우선", "abc", LENGTH_MESSAGE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} -> valid:false (password=\"{1}\")")
    @MethodSource("invalidPasswords")
    @DisplayName("비밀번호 정책을 위반해도 200을 반환하고 valid:false와 위반 메시지 1개를 반환하며, AuthService는 호출되지 않는다")
    void validate_invalidPassword_returns200WithValidFalse(
            String description, String password, String expectedMessage) throws Exception {
        // given
        String json = validatePasswordJson(password);

        // when & then
        mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(expectedMessage));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("password가 null이어도 500이 아니라 200과 길이 위반 메시지를 반환한다")
    void validate_nullPassword_returns200WithLengthMessage() throws Exception {
        // given
        String json = validatePasswordJson(null);

        // when & then
        mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(LENGTH_MESSAGE));

        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("password가 빈 문자열이면 200과 길이 위반 메시지를 반환한다")
    void validate_emptyPassword_returns200WithLengthMessage() throws Exception {
        // given
        String json = validatePasswordJson("");

        // when & then
        mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(LENGTH_MESSAGE));

        verifyNoInteractions(authService);
    }

    /**
     * {@code PasswordPolicy}는 {@code SignupRequest}(Bean Validation)와
     * {@code POST /api/auth/password/validate}가 공유하는 단일 출처다. 같은 비밀번호 문자열에 대해
     * 두 경로의 판정이 갈라지면 "가입 화면에선 통과인데 실제 가입은 실패"하는 사고로 이어지므로,
     * 두 엔드포인트의 결과(및 메시지)가 항상 일치하는지 교차 검증한다.
     *
     * <p>{@code null}·빈 문자열도 이 교차 검증에 포함한다: {@code SignupRequest.password}는
     * {@code @ValidPassword} 하나만 걸려 있고 {@code @NotBlank}는 걸려 있지 않다({@code ValidPassword}
     * Javadoc 참고). {@code PasswordPolicy#findViolation}이 {@code null}·{@code ""}를 길이 위반으로
     * 처리하므로, 두 경로 모두 위반 메시지는 정확히 1개이며 길이 메시지로 일치해야 한다.
     */
    @ParameterizedTest(name = "[{index}] {0}: password=\"{1}\" -> validate·signup 판정 일치")
    @MethodSource("crossCheckPasswords")
    @DisplayName("validate의 판정과 signup의 Bean Validation 결과가 동일한 비밀번호에 대해 일치한다")
    void validateAndSignup_agreeOnSamePassword(
            String description, String password, boolean expectedValid, String expectedMessage) throws Exception {
        // when: validate 호출
        String validateJson = validatePasswordJson(password);
        var validateResult = mockMvc.perform(post("/api/auth/password/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(expectedValid))
                .andExpect(jsonPath("$.data.message").value(expectedMessage));

        // when: signup 호출(동일 비밀번호)
        String signupJson = objectMapper.writeValueAsString(signupRequestWithPassword(password));
        var signupResultActions = mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signupJson));

        // then: valid:true면 signup은 400이 아니어야 하고, valid:false면 signup은 400 + 동일 메시지여야 한다
        if (expectedValid) {
            signupResultActions.andExpect(result -> {
                int status = result.getResponse().getStatus();
                org.junit.jupiter.api.Assertions.assertNotEquals(400, status,
                        "validate가 valid:true인 비밀번호는 signup에서 400(검증 실패)이면 안 된다");
            });
        } else {
            signupResultActions
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.password").value(expectedMessage));
        }
    }

    private static Stream<Arguments> crossCheckPasswords() {
        return Stream.of(
                Arguments.of("정책 만족", "abc123!@", true, VALID_MESSAGE),
                Arguments.of("길이 미달", "ab1!234", false, LENGTH_MESSAGE),
                Arguments.of("길이 초과", "abc123!@#$%^&", false, LENGTH_MESSAGE),
                Arguments.of("영문 없음", "12345678!", false, PATTERN_MESSAGE),
                Arguments.of("숫자 없음", "abcdefg!", false, PATTERN_MESSAGE),
                Arguments.of("특수문자 없음", "abc12345", false, PATTERN_MESSAGE),
                Arguments.of("공백은 특수문자 미인정", "abc12345 ", false, PATTERN_MESSAGE),
                Arguments.of("길이·구성 동시 위반 시 길이 메시지 우선", "abc", false, LENGTH_MESSAGE),
                Arguments.of("null은 길이 위반으로 처리", null, false, LENGTH_MESSAGE),
                Arguments.of("빈 문자열은 길이 위반으로 처리", "", false, LENGTH_MESSAGE)
        );
    }
}
