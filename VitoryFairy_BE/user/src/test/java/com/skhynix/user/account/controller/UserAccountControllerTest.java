package com.skhynix.user.account.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.global.error.GlobalExceptionHandler;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code DELETE /api/users/me}(회원 탈퇴)를 검증한다. 요구사항: {@code docs/requirements/user/withdraw.md}.
 *
 * <p>슬라이스 구성은 기존 {@code AuthController*Test}와 동일한 패턴을 따른다: {@code @WebMvcTest} +
 * {@code @ContextConfiguration(classes = UserAccountController.class)}로 {@code UserApplication}의
 * 자동 컨텍스트 병합(및 그로 인한 {@code entityManagerFactory} 부재 실패)을 우회하고, 필요한 빈만
 * {@code @Import}/{@code @MockitoBean}으로 명시한다.
 *
 * <p>이 슬라이스는 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를
 * 태우므로, {@link JwtTokenProvider}·{@link UserAccountRepository}를 목으로 제어해 "필터가 활성 계정만
 * 인증시킨다"는 규칙까지 컨트롤러 레이어에서 함께 검증한다(USER-WD-6·USER-WD-9의 핵심 방어선).
 */
@WebMvcTest(UserAccountController.class)
@ContextConfiguration(classes = UserAccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAccountControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAccountService userAccountService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private String stubValidAccessToken(String uid) {
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        return token;
    }

    @Test
    @DisplayName("[USER-WD-1, USER-WD-2] 유효한 access 토큰으로 DELETE /api/users/me를 호출하면 "
            + "본문 없이 204를 반환하고 토큰 subject가 해석된 내부 id로 서비스가 호출된다")
    void withdraw_validAccessToken_returns204NoBodyAndDelegatesToServiceWithResolvedId() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));

        // when & then
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userAccountService).withdraw(accountId);
    }

    @Test
    @DisplayName("[USER-WD-5] Authorization 헤더 없이 DELETE /api/users/me를 호출하면 401과 "
            + "\"인증이 필요합니다.\" 바디를 반환하고 서비스는 호출되지 않는다")
    void withdraw_noAuthorizationHeader_returns401AndDoesNotCallService() throws Exception {
        // when & then
        mockMvc.perform(delete("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userAccountService);
    }

    @Test
    @DisplayName("[USER-WD-6] 탈퇴 전에 발급받은 access 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 "
            + "DELETE /api/users/me를 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void withdraw_accessTokenIssuedBeforeWithdrawal_isRejectedWith401() throws Exception {
        // given: 토큰 자체는 유효하지만(만료 전) findActiveIdByUid가 빈 값을 반환한다 —
        // JwtAuthenticationFilter가 findIdByUid에서 findActiveIdByUid로 대체되며 탈퇴 계정을
        // "찾지 못함"으로 흡수하는 지점이다.
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userAccountService);
    }

    @Test
    @DisplayName("[USER-WD-9] 같은 access 토큰으로 DELETE /api/users/me를 연속 2회 호출하면 "
            + "1회차는 204, (탈퇴로 uid가 더 이상 활성 계정을 가리키지 않게 된) 2회차는 401이다")
    void withdraw_sameAccessTokenTwice_firstSucceedsSecondIsRejected() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        // 1회차 호출 시점엔 활성 계정이 조회되고, 탈퇴가 반영된 뒤인 2회차엔 더 이상 조회되지 않는다.
        given(userAccountRepository.findActiveIdByUid(uid))
                .willReturn(Optional.of(accountId), Optional.empty());

        // when & then: 1회차 204
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // when & then: 2회차 401, exit_at은 서비스가 다시 호출되지 않으므로 갱신될 여지가 없다
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verify(userAccountService, times(1)).withdraw(eq(accountId));
    }

    @Test
    @DisplayName("refresh 토큰으로 DELETE /api/users/me를 호출하면 인증되지 않아 401을 반환한다"
            + "(요구사항 ID 없음 — 필터가 refresh 토큰을 인증에 쓰지 않는다는 기존 규칙의 엔드포인트 차원 확인)")
    void withdraw_refreshToken_isRejectedWith401() throws Exception {
        // given
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        // when & then
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userAccountService);
    }
}
