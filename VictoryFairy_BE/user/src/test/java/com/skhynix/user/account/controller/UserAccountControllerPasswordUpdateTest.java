package com.skhynix.user.account.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.PasswordPolicy;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code PATCH /users/me/password}(외부 노출 경로 {@code /api/users/me/password})를 검증한다.
 * 요구사항: {@code docs/requirements/user/profile-edit.md}(USER-PE-1~7, 20~29, 32, 36~39).
 *
 * <p>슬라이스 구성은 {@code UserAccountControllerNicknameUpdateTest}와 동일한 패턴을 따른다.
 * 컨트롤러가 {@link UserAccountService}·{@link UserProfileService}도 생성자로 받으므로 함께 목으로
 * 등록해야 컨텍스트가 로딩된다(이 파일의 테스트는 그 둘과 상호작용하지 않는다).
 *
 * <p>refresh 토큰 만료(USER-PE-30·31)·access 토큰의 실제 지속(USER-PE-32)은 서비스 목 경계 밖의
 * 실제 저장소·필터 상태 변화라 이 슬라이스로는 증명할 수 없다 — {@code UserProfileEditServiceTest}가
 * "실패 시 issueTokens 미호출"까지 담당하고, 이 파일은 컨트롤러의 상태 코드·응답 매핑만 검증한다.
 */
@WebMvcTest(UserAccountController.class)
@ContextConfiguration(classes = UserAccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAccountControllerPasswordUpdateTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final String ENDPOINT = "/users/me/password";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileEditService userProfileEditService;

    @MockitoBean
    private UserProfileService userProfileService;

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

    private String authenticatedToken() {
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        return token;
    }

    private static String passwordJson(String currentPassword, String newPassword) {
        String current = currentPassword == null ? "null" : "\"" + currentPassword + "\"";
        String next = newPassword == null ? "null" : "\"" + newPassword + "\"";
        return "{\"currentPassword\":" + current + ",\"newPassword\":" + next + "}";
    }

    // ---------- 인증 (USER-PE-1 ~ 4) ----------

    @Test
    @DisplayName("[USER-PE-1] Authorization 헤더 없이 호출하면 401과 \"인증이 필요합니다.\" 바디를 반환하고 서비스는 호출되지 않는다")
    void updatePassword_noAuthorizationHeader_returns401AndDoesNotCallService() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-2] 위조·검증 실패 토큰으로 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void updatePassword_forgedToken_returns401AndDoesNotCallService() throws Exception {
        String token = "not-a-jwt";

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-3] refresh 토큰으로 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void updatePassword_refreshToken_returns401AndDoesNotCallService() throws Exception {
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-4] 탈퇴한 계정의 access 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 호출하면 "
            + "401을 반환하고 서비스는 호출되지 않는다")
    void updatePassword_withdrawnAccountToken_returns401AndDoesNotCallService() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    // ---------- 성공 (USER-PE-22, 32, 36, 37) ----------

    @Test
    @DisplayName("[USER-PE-22] 성공하면 200과 ApiResponse<TokenResponse>를 반환하고, data는 정확히 "
            + "accessToken·refreshToken 2개 키만 가지며 서비스가 반환한 값과 일치한다")
    void updatePassword_validRequest_returns200WithExactlyTwoTokenKeys() throws Exception {
        // given
        String token = authenticatedToken();
        given(userProfileEditService.updatePassword(eq(1L), eq("Old1234!"), eq("New1234!")))
                .willReturn(new TokenResponse("new-access-token", "new-refresh-token"));

        // when & then
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));

        verify(userProfileEditService).updatePassword(eq(1L), eq("Old1234!"), eq("New1234!"));
    }

    // ---------- 필수 필드 (USER-PE-20) ----------

    @Test
    @DisplayName("[USER-PE-20] currentPassword를 생략하면 성공하지 않는다 — 서비스가 null로 호출되고 "
            + "(형식 검증 애노테이션이 없어 컨트롤러 진입은 통과) INVALID_CURRENT_PASSWORD 400을 그대로 응답한다")
    void updatePassword_missingCurrentPassword_doesNotSucceed() throws Exception {
        // given
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD))
                .given(userProfileEditService).updatePassword(eq(1L), isNull(), eq("New1234!"));

        // when & then
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"New1234!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."));

        verify(userProfileEditService).updatePassword(eq(1L), isNull(), eq("New1234!"));
    }

    // ---------- 형식 위반 (USER-PE-24 ~ 26, 29) ----------

    @Test
    @DisplayName("[USER-PE-25] 새 비밀번호가 8~12자를 위반하면 400과 data.newPassword에 길이 위반 메시지를 반환하고 "
            + "서비스는 호출되지 않는다")
    void updatePassword_newPasswordLengthViolation_returns400WithLengthMessageAndSkipsService() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "a1!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.newPassword").value(PasswordPolicy.LENGTH_MESSAGE))
                .andExpect(jsonPath("$.data.length()").value(1));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-26] 새 비밀번호가 영문/숫자/특수문자 중 하나라도 빠지면 400과 data.newPassword에 "
            + "문자 구성 위반 메시지를 반환한다")
    void updatePassword_newPasswordPatternViolation_returns400WithPatternMessage() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "abcdefgh")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.newPassword").value(PasswordPolicy.PATTERN_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-29] 새 비밀번호 형식 위반과 현재 비밀번호 오답을 동시에 보내면 400이며 응답은 "
            + "data.newPassword 형식 메시지 하나뿐이다(\"현재 비밀번호가 올바르지 않습니다.\"가 함께 나오지 않는다) — "
            + "형식 검증이 컨트롤러 진입 전에 끝나 서비스는 아예 호출되지 않는다")
    void updatePassword_formatViolationAndWrongCurrentPassword_returnsOnlyFormatMessage() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("WrongPassword!", "abcdefgh")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.newPassword").value(PasswordPolicy.PATTERN_MESSAGE))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."));

        verifyNoInteractions(userProfileEditService);
    }

    // ---------- 서비스 예외 매핑 (USER-PE-27, 28, 31, 38) ----------

    @Test
    @DisplayName("[USER-PE-27, 31, 38] 서비스가 INVALID_CURRENT_PASSWORD를 던지면 400을 반환하고 응답에 "
            + "accessToken·refreshToken 키가 없다(새 토큰 미발급)")
    void updatePassword_wrongCurrentPassword_returns400WithoutTokens() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD))
                .given(userProfileEditService).updatePassword(eq(1L), eq("Wrong1234!"), eq("New1234!"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Wrong1234!", "New1234!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 올바르지 않습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PE-28, 38] 서비스가 SAME_AS_CURRENT_PASSWORD를 던지면 400과 안내 메시지를 반환하고 "
            + "새 토큰이 발급되지 않는다")
    void updatePassword_sameAsCurrentPassword_returns400WithoutTokens() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.SAME_AS_CURRENT_PASSWORD))
                .given(userProfileEditService).updatePassword(eq(1L), eq("Same1234!"), eq("Same1234!"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Same1234!", "Same1234!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("현재 비밀번호와 다른 비밀번호를 사용해 주세요."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------- 응답에 민감 키가 없다 (USER-PE-6, 39) ----------

    @Test
    @DisplayName("[USER-PE-6] 성공 응답 data에는 currentPassword·newPassword·password 키가 없다")
    void updatePassword_successResponse_hasNoPasswordKeys() throws Exception {
        String token = authenticatedToken();
        given(userProfileEditService.updatePassword(eq(1L), eq("Old1234!"), eq("New1234!")))
                .willReturn(new TokenResponse("new-access-token", "new-refresh-token"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentPassword").doesNotExist())
                .andExpect(jsonPath("$.data.newPassword").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PE-39] 성공 응답 data는 정확히 accessToken·refreshToken 2개 키뿐이라 uid가 별도 키로는 없다 "
            + "(토큰 payload의 sub는 login·refresh와 같은 이미 알려진 노출이라 이 계약 위반이 아니다)")
    void updatePassword_successResponse_hasNoUidKey() throws Exception {
        String token = authenticatedToken();
        given(userProfileEditService.updatePassword(eq(1L), eq("Old1234!"), eq("New1234!")))
                .willReturn(new TokenResponse("new-access-token", "new-refresh-token"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Old1234!", "New1234!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("[USER-PE-6, 39] 실패 응답의 data는 null이라 password·uid 키를 담을 수 없다")
    void updatePassword_failureResponse_hasNullData() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD))
                .given(userProfileEditService).updatePassword(eq(1L), eq("Wrong1234!"), eq("New1234!"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("Wrong1234!", "New1234!")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
