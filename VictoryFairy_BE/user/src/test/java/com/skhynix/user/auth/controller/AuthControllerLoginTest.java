package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.LoginRequest;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /auth/login}의 계정 열거 차단 계약({@code docs/requirements/user/login-account-enumeration.md},
 * USER-LAE-*) 중 컨트롤러 경계에서 관측 가능한 부분을 검증한다. 서비스 계층(더미 해시 검증 1회 수행 등)은
 * {@code AuthServiceTest}·{@code AuthServiceDummyHashTest} 소관이다. 슬라이스 구성은
 * {@code AuthControllerAuthenticationEntryPointTest}와 동일한 패턴을 따른다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerLoginTest {

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

    // AuthController가 가입 전 임시 프로필 이미지 업로드용 TempProfileImageService도 생성자로 받아,
    // 없으면 컨텍스트 로딩이 실패한다(이 클래스 테스트는 그 경로와 상호작용하지 않음).
    @MockitoBean
    private com.skhynix.user.profileimage.service.TempProfileImageService tempProfileImageService;

    @Test
    @DisplayName("[USER-LAE-10] email이 형식(@Email)을 위반하고 password가 빈값이면 400을 반환하고 "
            + "AuthService.login()은 전혀 호출되지 않는다 — 비밀번호 검증 자체가 일어나지 않는다")
    void login_invalidFormat_returns400WithoutCallingService() throws Exception {
        // given
        String json = objectMapper.writeValueAsString(new LoginRequest("not-an-email", ""));

        // when & then
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(authService, never()).login(any());
    }

    @Test
    @DisplayName("[USER-LAE-2] 미가입 이메일로 로그인하면 401과 지정된 실패 본문을 그대로 반환한다")
    void login_emailNotFound_returns401WithSpecifiedBody() throws Exception {
        // given
        given(authService.login(any())).willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        String json = objectMapper.writeValueAsString(new LoginRequest("nobody@example.com", "anyPassw0rd!"));

        // when & then
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(INVALID_CREDENTIALS_MESSAGE));
    }

    @Test
    @DisplayName("[USER-LAE-7] 미가입·탈퇴·오답 세 갈래 모두 컨트롤러 경계에서 같은 BusinessException으로 "
            + "던져지므로(서비스가 갈래를 구분하지 않고 동일 예외를 던짐), 세 응답의 상태코드·본문 바이트· "
            + "Content-Type이 완전히 동일하다")
    void login_threeFailureBranches_produceByteIdenticalResponses() throws Exception {
        // given: 세 갈래(미가입·탈퇴·오답) 모두 서비스는 같은 BusinessException(INVALID_CREDENTIALS)을
        // 던진다 — AuthServiceTest가 이미 이 사실 자체(세 갈래가 같은 ErrorCode)를 확인했으므로, 여기서는
        // 컨트롤러가 그 동일 예외를 서로 다른 응답으로 새어 나가게 하지 않는지만 본다.
        LoginRequest notFoundRequest = new LoginRequest("notfound@example.com", "Passw0rd!");
        LoginRequest withdrawnRequest = new LoginRequest("withdrawn@example.com", "Passw0rd!");
        LoginRequest wrongPasswordRequest = new LoginRequest("registered@example.com", "wrongPassw0rd!");
        given(authService.login(notFoundRequest)).willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        given(authService.login(withdrawnRequest)).willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        given(authService.login(wrongPasswordRequest))
                .willThrow(new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        // when
        MvcResult notFoundResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notFoundRequest)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult withdrawnResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawnRequest)))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult wrongPasswordResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongPasswordRequest)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // then: 상태코드는 이미 위에서 401로 동일함을 확인했다. 본문 바이트와 Content-Type도 동일해야
        // 갈래를 구분할 수 있는 신호가 없다는 USER-LAE-7의 인수 기준을 만족한다.
        String notFoundBody = notFoundResult.getResponse().getContentAsString();
        String withdrawnBody = withdrawnResult.getResponse().getContentAsString();
        String wrongPasswordBody = wrongPasswordResult.getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(notFoundBody).isEqualTo(withdrawnBody);
        org.assertj.core.api.Assertions.assertThat(withdrawnBody).isEqualTo(wrongPasswordBody);
        org.assertj.core.api.Assertions.assertThat(notFoundResult.getResponse().getContentType())
                .isEqualTo(withdrawnResult.getResponse().getContentType());
        org.assertj.core.api.Assertions.assertThat(withdrawnResult.getResponse().getContentType())
                .isEqualTo(wrongPasswordResult.getResponse().getContentType());
    }
}
