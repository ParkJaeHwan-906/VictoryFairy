package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.NicknameValidationRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
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
 * {@code POST /api/auth/nickname/validate}가 계약대로 동작하는지 확인한다: 인증 없이 접근 가능하고
 * (USER-NICK-2), 정책 위반·중복 어느 경우든 항상 HTTP 200을 반환하며(USER-NICK-3, 5, 12, 15),
 * 정책 위반 시 signup의 409 중복과 상태 코드가 다름을 확인한다(USER-NICK-14, 15).
 *
 * <p>슬라이스 구성은 {@code AuthControllerPasswordValidateTest}와 동일한 패턴을 따른다. 이 컨트롤러는
 * {@link AuthService#validateNickname(String)}을 거치므로(비밀번호 사전 검사와 달리 DB를 조회한다),
 * 여기서는 {@code AuthService}를 목으로 대체해 컨트롤러의 상태 코드·응답 JSON 매핑만 검증한다.
 * 정책 판정 로직 자체({@code NicknamePolicy})는 {@code NicknamePolicyTest}가, 정책→중복 2단
 * 파이프라인 오케스트레이션은 {@code AuthServiceValidateNicknameTest}가 담당한다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerNicknameValidateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private String validateNicknameJson(String nickname) throws Exception {
        return objectMapper.writeValueAsString(new NicknameValidationRequest(nickname));
    }

    @Test
    @DisplayName("[USER-NICK-2] Authorization 헤더 없이 호출해도 401이 아니라 정상 응답(200)을 반환한다")
    void validateNickname_withoutAuthorizationHeader_returns200NotUnauthorized() throws Exception {
        // given
        given(authService.validateNickname("길동gil9")).willReturn(NicknameValidationResponse.passed());
        String json = validateNicknameJson("길동gil9");

        // when & then: Authorization 헤더를 일부러 붙이지 않는다.
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[USER-NICK-3] 정책을 통과하고 미점유인 닉네임은 200과 valid:true, 통과 메시지를 반환한다")
    void validateNickname_policyPassedAndNotDuplicated_returns200WithValidTrue() throws Exception {
        // given
        given(authService.validateNickname("길동gil9")).willReturn(NicknameValidationResponse.passed());
        String json = validateNicknameJson("길동gil9");

        // when & then
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.message").value(NicknamePolicy.VALID_MESSAGE));

        verify(authService).validateNickname(eq("길동gil9"));
    }

    @Test
    @DisplayName("[USER-NICK-5] 정책 위반 닉네임은 200과 valid:false, 위반 메시지 1개를 반환한다(400 아님)")
    void validateNickname_policyViolation_returns200WithValidFalse() throws Exception {
        // given
        given(authService.validateNickname("hi!"))
                .willReturn(NicknameValidationResponse.violated(NicknamePolicy.PATTERN_MESSAGE));
        String json = validateNicknameJson("hi!");

        // when & then
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(NicknamePolicy.PATTERN_MESSAGE));
    }

    @Test
    @DisplayName("[USER-NICK-7] 길이 위반 닉네임은 200과 길이 위반 메시지를 반환한다")
    void validateNickname_lengthViolation_returns200WithLengthMessage() throws Exception {
        // given
        String nickname = "가나다라마바사아자차카"; // 11자
        given(authService.validateNickname(nickname))
                .willReturn(NicknameValidationResponse.violated(NicknamePolicy.LENGTH_MESSAGE));
        String json = validateNicknameJson(nickname);

        // when & then
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(NicknamePolicy.LENGTH_MESSAGE));
    }

    @Test
    @DisplayName("[USER-NICK-10] nickname이 null이어도 500이 아니라 200과 길이 위반 메시지를 반환한다")
    void validateNickname_nullNickname_returns200WithLengthMessage() throws Exception {
        // given
        given(authService.validateNickname(null))
                .willReturn(NicknameValidationResponse.violated(NicknamePolicy.LENGTH_MESSAGE));
        String json = validateNicknameJson(null);

        // when & then
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value(NicknamePolicy.LENGTH_MESSAGE));
    }

    @Test
    @DisplayName("[USER-NICK-12] 정책은 통과했지만 이미 사용 중인 닉네임은 200과 중복 메시지를 반환한다")
    void validateNickname_duplicated_returns200WithDuplicateMessage() throws Exception {
        // given
        given(authService.validateNickname("이미있음"))
                .willReturn(NicknameValidationResponse.violated(ErrorCode.DUPLICATE_NICKNAME.getMessage()));
        String json = validateNicknameJson("이미있음");

        // when & then
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value("이미 사용 중인 닉네임입니다."));
    }

    /**
     * [USER-NICK-14, USER-NICK-15] 탈퇴 계정이 점유한 닉네임 시나리오: validate는 200 + 중복 메시지를
     * 반환하지만, 같은 닉네임으로 signup을 호출하면 (existsByNickname이 exit_at을 거르지 않아) 409
     * DUPLICATE_NICKNAME으로 거절된다. 두 엔드포인트의 중복 상태 코드가 의도적으로 다름을 확인한다.
     */
    @Test
    @DisplayName("[USER-NICK-14, USER-NICK-15] 중복 닉네임은 validate에서 200, signup에서는 409로 상태 코드가 다르다")
    void validateNickname_duplicated_returns200_whileSignupWithSameNicknameReturns409() throws Exception {
        // given
        String duplicatedNickname = "탈퇴자닉네임";
        given(authService.validateNickname(duplicatedNickname))
                .willReturn(NicknameValidationResponse.violated(ErrorCode.DUPLICATE_NICKNAME.getMessage()));
        given(authService.signup(org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME));

        // when & then: validate는 200
        mockMvc.perform(post("/api/auth/nickname/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validateNicknameJson(duplicatedNickname)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value("이미 사용 중인 닉네임입니다."));

        // when & then: 같은 닉네임으로 signup은 409
        SignupRequest signupRequest = new SignupRequest(
                "홍길동", "01012345678", "test@example.com",
                com.skhynix.domain.user.entity.Gender.MALE, duplicatedNickname, "abc123!@");
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."));
    }
}
