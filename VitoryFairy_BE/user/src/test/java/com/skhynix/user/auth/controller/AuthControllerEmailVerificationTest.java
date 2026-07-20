package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.EmailSendCodeRequest;
import com.skhynix.user.auth.dto.EmailVerifyRequest;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.global.error.GlobalExceptionHandler;
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
 * {@code POST /api/auth/email/send-code}·{@code POST /api/auth/email/verify}가 요구사항
 * {@code docs/requirements/user/email-verification.md}(USER-EMV-*)대로 동작하는지 검증한다.
 *
 * <p>슬라이스 구성(컨텍스트 자동 병합 우회, {@code UserAccountRepository}를 {@code @MockitoBean}으로
 * 대체하는 이유)은 {@code AuthControllerSignupTest}의 Javadoc과 동일한 패턴을 따른다. 이 슬라이스는 실제
 * {@link SecurityConfig}를 {@code @Import}해 {@code /api/auth/**}의 {@code permitAll} 규칙이 두 신규
 * 엔드포인트에도 실제로 적용되는지(USER-EMV-7)까지 함께 검증한다. 저장소({@code EmailVerificationStore}·
 * Redis)는 {@link EmailVerificationService} 자체를 목으로 대체해 이 레이어의 관심사(요청 검증·상태코드·
 * 에러코드 매핑)만 격리해서 본다 — 정책 판정(쿨다운·시도횟수 등) 자체는 {@code EmailVerificationServiceTest}
 * 소관이다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerEmailVerificationTest {

    private static final String VALIDATION_MESSAGE = "입력값이 올바르지 않습니다.";

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

    // ---------- POST /api/auth/email/send-code ----------

    @Test
    @DisplayName("[USER-EMV-1, USER-EMV-7] 형식이 유효한 이메일로 발송을 요청하면 인증 없이도 200을 반환하고 "
            + "서비스에 이메일을 그대로 위임한다")
    void sendCode_validEmail_returns200WithoutAuthenticationAndDelegatesToService() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest("user@example.com"));

        // when & then: Authorization 헤더 없이 호출
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(emailVerificationService).sendCode("user@example.com");
    }

    @Test
    @DisplayName("[USER-EMV-4] 이메일이 빈값이면 400과 Bean Validation 메시지를 반환하고 서비스는 호출되지 않는다")
    void sendCode_blankEmail_returns400WithoutCallingService() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest(""));

        // when & then
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(VALIDATION_MESSAGE))
                .andExpect(jsonPath("$.data.email").exists());

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("[USER-EMV-4] 이메일 형식이 @Email을 위반하면 400과 Bean Validation 메시지를 반환하고 서비스는 호출되지 않는다")
    void sendCode_malformedEmail_returns400WithoutCallingService() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest("not-an-email"));

        // when & then
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(VALIDATION_MESSAGE))
                .andExpect(jsonPath("$.data.email").exists());

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("이메일이 100자를 초과하면 400과 Bean Validation 메시지를 반환한다"
            + "(요구사항 문서 미기재 — DTO @Size(max=100) 경계 확인)")
    void sendCode_emailExceeds100Chars_returns400() throws Exception {
        // given
        String localPart = "a".repeat(96);
        String tooLongEmail = localPart + "@a.co"; // 101자, @Size(max=100) 초과
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest(tooLongEmail));

        // when & then
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").exists());

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("[USER-EMV-5] 쿨다운 중에 재발송을 요청하면 429와 재시도 안내 메시지를 반환한다")
    void sendCode_serviceThrowsCooldown_returns429WithMessage() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.EMAIL_SEND_COOLDOWN))
                .when(emailVerificationService).sendCode("user@example.com");
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest("user@example.com"));

        // when & then
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.EMAIL_SEND_COOLDOWN.getMessage()));
    }

    @Test
    @DisplayName("[USER-EMV-14] 이미 가입된 이메일로 발송을 요청하면 409와 중복 안내 메시지를 반환한다")
    void sendCode_serviceThrowsDuplicateEmail_returns409WithMessage() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL))
                .when(emailVerificationService).sendCode("registered@example.com");
        String json = objectMapper.writeValueAsString(new EmailSendCodeRequest("registered@example.com"));

        // when & then
        mockMvc.perform(post("/api/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_EMAIL.getMessage()));
    }

    // ---------- POST /api/auth/email/verify ----------

    @Test
    @DisplayName("[USER-EMV-7, USER-EMV-8] 유효기간 내 올바른 인증번호로 검증을 요청하면 인증 없이도 200을 반환한다")
    void verify_correctCode_returns200WithoutAuthentication() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("user@example.com", "123456"));

        // when & then: Authorization 헤더 없이 호출
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(emailVerificationService).verify("user@example.com", "123456");
    }

    @Test
    @DisplayName("[USER-EMV-13] email과 code가 모두 빈값이면 400과 두 필드의 Bean Validation 메시지를 반환하고 서비스는 호출되지 않는다")
    void verify_blankEmailAndCode_returns400WithBothFieldMessages() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("", ""));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(VALIDATION_MESSAGE))
                .andExpect(jsonPath("$.data.email").exists())
                .andExpect(jsonPath("$.data.code").exists());

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("[USER-EMV-13] code가 6자리 숫자가 아니면(5자리) 400과 형식 위반 메시지를 반환하고 서비스는 호출되지 않는다")
    void verify_codeNotSixDigits_returns400WithoutCallingService() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("user@example.com", "12345"));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("인증번호는 6자리 숫자여야 합니다."));

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("[USER-EMV-13] code에 숫자가 아닌 문자가 섞이면 400과 형식 위반 메시지를 반환한다")
    void verify_codeContainsNonDigit_returns400() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("user@example.com", "12a45b"));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value("인증번호는 6자리 숫자여야 합니다."));

        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("[USER-EMV-10] 인증번호가 불일치하면 400과 불일치 메시지를 반환한다")
    void verify_serviceThrowsInvalidCode_returns400WithMessage() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.INVALID_VERIFICATION_CODE))
                .when(emailVerificationService).verify("user@example.com", "999999");
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("user@example.com", "999999"));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_VERIFICATION_CODE.getMessage()));
    }

    @Test
    @DisplayName("[USER-EMV-11] 유효한 인증번호가 없으면(미발송·만료·이미 사용됨) 400과 만료/무효 메시지를 반환한다")
    void verify_serviceThrowsExpiredCode_returns400WithMessage() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.EXPIRED_VERIFICATION_CODE))
                .when(emailVerificationService).verify("never-sent@example.com", "123456");
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("never-sent@example.com", "123456"));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.EXPIRED_VERIFICATION_CODE.getMessage()));
    }

    @Test
    @DisplayName("[USER-EMV-12] 검증 실패가 5회를 초과하면 400과 시도 초과 메시지를 반환한다")
    void verify_serviceThrowsAttemptsExceeded_returns400WithMessage() throws Exception {
        // given
        doThrow(new BusinessException(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED))
                .when(emailVerificationService).verify(eq("user@example.com"), eq("123456"));
        String json = objectMapper.writeValueAsString(new EmailVerifyRequest("user@example.com", "123456"));

        // when & then
        mockMvc.perform(post("/api/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED.getMessage()));
    }

    // ---------- signup 연동(USER-EMV-16) — AuthController 배선 확인 ----------

    @Test
    @DisplayName("[USER-EMV-16, USER-EMV-17] 이메일 인증완료 상태가 아니면 signup은 400과 "
            + "이메일 미인증 메시지를 반환한다(미인증·만료 모두 AuthService가 동일 코드로 던짐)")
    void signup_emailNotVerified_returns400WithMessage() throws Exception {
        // given: 이 슬라이스는 AuthService를 목으로 대체하므로, AuthService가 EMAIL_NOT_VERIFIED를
        // 던지는 상황을 그대로 재현해 AuthController -> GlobalExceptionHandler 배선을 확인한다.
        // 정책 판정 자체(EmailVerificationService.isEmailVerified 분기)는 AuthServiceTest 소관이다.
        given(authService.signup(org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED));
        String json = objectMapper.writeValueAsString(new com.skhynix.user.auth.dto.SignupRequest(
                "홍길동", "01012345678", "unverified@example.com",
                com.skhynix.domain.user.entity.Gender.MALE, "nickname", "abc123!@"));

        // when & then
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.EMAIL_NOT_VERIFIED.getMessage()));

        verify(emailVerificationService, never()).consumeVerified(org.mockito.ArgumentMatchers.anyString());
    }
}
