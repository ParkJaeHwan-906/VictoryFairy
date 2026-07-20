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
import com.skhynix.user.auth.policy.NicknamePolicy;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
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
 * {@code POST /api/auth/signup}의 {@link SignupRequest#nickname()} 검증 정책이 서블릿 레이어
 * (@Valid + GlobalExceptionHandler)까지 실제로 적용되는지 확인한다. {@code AuthControllerSignupTest}와
 * 동일한 슬라이스 구성 패턴을 따른다({@code docs/requirements/user/nickname-policy.md} USER-NICK-4, 6, 10).
 */
@WebMvcTest(AuthController.class)
@ContextConfiguration(classes = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerSignupNicknameTest {

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

    private SignupRequest requestWithNickname(String nickname) {
        return new SignupRequest("홍길동", "01012345678", "test@example.com", Gender.MALE, nickname, "abc123!@");
    }

    @Test
    @DisplayName("[USER-NICK-4] 정책을 만족하는 nickname으로 회원가입하면 400(nickname 사유)이 아니라 "
            + "201을 반환하고 AuthService.signup()이 호출된다")
    void signup_validNickname_returns201AndCallsService() throws Exception {
        // given
        given(authService.signup(any())).willReturn(1L);
        String json = objectMapper.writeValueAsString(requestWithNickname("길동gil9"));

        // when & then
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(content().string("true"));

        verify(authService).signup(any());
    }

    private static Stream<Arguments> invalidNicknames() {
        return Stream.of(
                // description, nickname, 유발되는 규칙의 메시지
                Arguments.of("길이 미달(0자)", "", NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("길이 초과(11자)", "가나다라마바사아자차카", NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("공백 포함(문자 구성 위반)", "a b", NicknamePolicy.PATTERN_MESSAGE),
                Arguments.of("특수문자 포함(문자 구성 위반)", "hi!", NicknamePolicy.PATTERN_MESSAGE),
                Arguments.of("이모지 포함(문자 구성 위반)", "굿🎉", NicknamePolicy.PATTERN_MESSAGE),
                Arguments.of("길이·구성 동시 위반 시 길이 메시지가 우선", "!@#$%^&*()!", NicknamePolicy.LENGTH_MESSAGE),
                Arguments.of("null은 길이 위반으로 처리(@NotBlank 없이 @ValidNickname 단독 책임)", null, NicknamePolicy.LENGTH_MESSAGE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} -> 400 (nickname=\"{1}\")")
    @MethodSource("invalidNicknames")
    @DisplayName("[USER-NICK-6, USER-NICK-10] 닉네임 정책을 위반하면 400과 위반 메시지를 data.nickname에 담아 "
            + "반환하고, AuthService는 호출되지 않는다")
    void signup_invalidNickname_returns400WithoutCallingService(
            String description, String nickname, String expectedMessage) throws Exception {
        // given
        String json = objectMapper.writeValueAsString(requestWithNickname(nickname));

        // when & then
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.nickname").value(expectedMessage));

        verifyNoInteractions(authService);
    }
}
