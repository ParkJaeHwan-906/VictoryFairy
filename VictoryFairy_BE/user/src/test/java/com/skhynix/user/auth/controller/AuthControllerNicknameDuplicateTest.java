package com.skhynix.user.auth.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.auth.dto.NicknameValidationRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
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
 * {@code POST /api/auth/nickname/duplicate}가 계약대로 동작하는지 확인한다: 인증 없이 접근 가능하고
 * (permitAll), 중복·미중복 어느 경우든 항상 HTTP 200을 반환한다({@code @Valid} 미적용).
 *
 * <p>슬라이스 구성은 {@code AuthControllerNicknameValidateTest}와 동일한 패턴을 따른다. 이 컨트롤러는
 * {@link AuthService#checkNicknameDuplicate(String)}을 거치므로 여기서는 {@code AuthService}를 목으로
 * 대체해 컨트롤러의 상태 코드·응답 JSON 매핑만 검증한다. 정책 검사가 아예 없다는 서비스 계층의 핵심
 * 계약(정책 위반이어도 미점유면 valid:true)은 {@code AuthServiceCheckNicknameDuplicateTest}가 담당한다.
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerNicknameDuplicateTest {

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

    private String duplicateCheckJson(String nickname) throws Exception {
        return objectMapper.writeValueAsString(new NicknameValidationRequest(nickname));
    }

    @Test
    @DisplayName("미중복 닉네임은 200과 valid:true, 통과 메시지를 반환한다")
    void checkNicknameDuplicate_notDuplicated_returns200WithValidTrue() throws Exception {
        // given
        given(authService.checkNicknameDuplicate("길동gil9")).willReturn(NicknameValidationResponse.passed());
        String json = duplicateCheckJson("길동gil9");

        // when & then
        mockMvc.perform(post("/api/auth/nickname/duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.message").value(NicknamePolicy.VALID_MESSAGE));

        verify(authService).checkNicknameDuplicate(eq("길동gil9"));
    }

    @Test
    @DisplayName("중복(점유) 닉네임은 200과 valid:false, 중복 메시지를 반환한다(409 아님)")
    void checkNicknameDuplicate_duplicated_returns200WithValidFalse() throws Exception {
        // given
        given(authService.checkNicknameDuplicate("이미있음"))
                .willReturn(NicknameValidationResponse.violated(ErrorCode.DUPLICATE_NICKNAME.getMessage()));
        String json = duplicateCheckJson("이미있음");

        // when & then
        mockMvc.perform(post("/api/auth/nickname/duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.message").value("이미 사용 중인 닉네임입니다."));

        verify(authService).checkNicknameDuplicate(eq("이미있음"));
    }

    @Test
    @DisplayName("Authorization 헤더 없이 호출해도 401이 아니라 정상 응답(200)을 반환한다")
    void checkNicknameDuplicate_withoutAuthorizationHeader_returns200NotUnauthorized() throws Exception {
        // given
        given(authService.checkNicknameDuplicate("길동gil9")).willReturn(NicknameValidationResponse.passed());
        String json = duplicateCheckJson("길동gil9");

        // when & then: Authorization 헤더를 일부러 붙이지 않는다.
        mockMvc.perform(post("/api/auth/nickname/duplicate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }
}
