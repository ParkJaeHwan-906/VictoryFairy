package com.skhynix.user.account.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.dto.NicknameChangeCooldownResponse;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.auth.policy.NicknamePolicy;
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
 * {@code PATCH /users/me/nickname}(외부 노출 경로 {@code /api/users/me/nickname})을 검증한다.
 * 요구사항: {@code docs/requirements/user/profile-edit.md}(USER-PE-1~19, 33·34).
 *
 * <p>슬라이스 구성은 {@code UserAccountControllerMeTest}·{@code UserAccountControllerTest}와 동일한
 * 패턴을 따른다 — {@code @WebMvcTest} + {@code @ContextConfiguration(classes = UserAccountController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다.
 * 컨트롤러가 {@link UserAccountService}·{@link UserProfileService}도 생성자로 받으므로 함께 목으로
 * 등록해야 컨텍스트가 로딩된다(이 파일의 테스트는 그 둘과 상호작용하지 않는다).
 *
 * <p>실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우므로,
 * USER-PE-1~4의 401 판정을 필터 레벨에서 함께 검증한다. {@code @Valid}(→ {@code NicknamePolicy}) 위반은
 * 스프링이 컨트롤러 진입 전에 걸러 서비스가 호출되지 않는다는 점(USER-PE-15)까지 검증한다.
 */
@WebMvcTest(UserAccountController.class)
@ContextConfiguration(classes = UserAccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAccountControllerNicknameUpdateTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final String ENDPOINT = "/users/me/nickname";

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

    // 컨트롤러가 프로필 이미지 업로드용 AccountProfileImageService도 생성자로 받아, 없으면 컨텍스트
    // 로딩이 실패한다(이 클래스 테스트는 닉네임 수정만 다뤄 상호작용은 없음).
    @MockitoBean
    private com.skhynix.user.profileimage.service.AccountProfileImageService accountProfileImageService;

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

    // ---------- 인증 (USER-PE-1 ~ 4) ----------

    @Test
    @DisplayName("[USER-PE-1] Authorization 헤더 없이 호출하면 401과 \"인증이 필요합니다.\" 바디를 반환하고 서비스는 호출되지 않는다")
    void updateNickname_noAuthorizationHeader_returns401AndDoesNotCallService() throws Exception {
        mockMvc.perform(patch(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-2] 위조·검증 실패 토큰으로 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void updateNickname_forgedToken_returns401AndDoesNotCallService() throws Exception {
        String token = "not-a-jwt"; // 스텁 없음 = validateToken() 기본값 false

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-3] refresh 토큰으로 호출하면 401을 반환하고 서비스는 호출되지 않는다")
    void updateNickname_refreshToken_returns401AndDoesNotCallService() throws Exception {
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-4] 탈퇴한 계정의 access 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 호출하면 "
            + "401을 반환하고 서비스는 호출되지 않는다")
    void updateNickname_withdrawnAccountToken_returns401AndDoesNotCallService() throws Exception {
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    // ---------- 성공 (USER-PE-8, 9) ----------

    @Test
    @DisplayName("[USER-PE-8, 9] 인증된 사용자가 정책을 만족하는 새 닉네임으로 요청하면 본문 없이 204를 반환하고 "
            + "서비스가 토큰 주체의 내부 id와 요청 닉네임으로 호출된다")
    void updateNickname_validRequest_returns204NoBodyAndDelegatesToService() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));

        // when & then
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userProfileEditService).updateNickname(eq(accountId), eq("길동gil9"));
    }

    @Test
    @DisplayName("[USER-PE-5, USER-PE-34] 본문에 userId·uid 등 식별자를 추가로 넣어도 무시되고 토큰 주체 본인의 "
            + "id로만 서비스가 호출되며, 비밀번호 없이도 요청이 성공한다")
    void updateNickname_extraBodyFields_areIgnoredAndOnlyTokenSubjectIdIsUsed() throws Exception {
        // given
        String token = authenticatedToken();

        // when & then: userId·uid 필드는 NicknameUpdateRequest에 없는 키라 무시된다
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\",\"userId\":999,\"uid\":\"someone-elses-uid\"}"))
                .andExpect(status().isNoContent());

        verify(userProfileEditService).updateNickname(eq(1L), eq("길동gil9"));
    }

    // ---------- 형식 위반 (USER-PE-11 ~ 15, 33) ----------

    @Test
    @DisplayName("[USER-PE-12] 11자 닉네임(길이 위반)은 400과 data.nickname에 길이 위반 메시지를 담아 반환하고 "
            + "서비스는 호출되지 않는다")
    void updateNickname_lengthViolation_returns400WithLengthMessageAndSkipsService() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"가나다라마바사아자차카\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.LENGTH_MESSAGE))
                .andExpect(jsonPath("$.data.length()").value(1));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-13] 허용 문자 외 문자가 포함된 닉네임은 400과 data.nickname에 문자 구성 위반 메시지를 반환한다")
    void updateNickname_patternViolation_returns400WithPatternMessage() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"a b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.PATTERN_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-14] 본문에 nickname이 없으면 400과 길이 위반 메시지를 반환한다(@NotBlank류 메시지가 아니다)")
    void updateNickname_missingNickname_returns400WithLengthMessage() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.LENGTH_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-14] nickname이 null이면 400과 길이 위반 메시지를 반환한다")
    void updateNickname_nullNickname_returns400WithLengthMessage() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.LENGTH_MESSAGE));

        verifyNoInteractions(userProfileEditService);
    }

    @Test
    @DisplayName("[USER-PE-15, USER-PE-33] 형식 위반이면서 이미 점유된 값과 같은 문자열이라도 409가 아니라 400과 형식 "
            + "메시지 하나만 반환한다(형식 위반이면 동일 여부·중복 조회를 아예 타지 않는다)")
    void updateNickname_formatViolation_neverReaches409EvenIfStringLooksDuplicated() throws Exception {
        String token = authenticatedToken();

        // given: 서비스가 호출됐다면 409를 던지도록 스텁해 둔다 — 컨트롤러가 실제로 서비스를 호출하지
        // 않는다는 것을 증명하기 위한 안전장치(스텁을 타면 테스트가 실패해야 정상).
        willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("hi!"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"hi!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.PATTERN_MESSAGE));

        verify(userProfileEditService, never()).updateNickname(eq(1L), eq("hi!"));
    }

    // ---------- 서비스 예외 매핑 (USER-PE-16, 17) ----------

    @Test
    @DisplayName("[USER-PE-16] 서비스가 DUPLICATE_NICKNAME을 던지면 409와 \"이미 사용 중인 닉네임입니다.\"를 반환한다")
    void updateNickname_duplicatedByOtherAccount_returns409() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("이미있음"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"이미있음\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 닉네임입니다."));
    }

    @Test
    @DisplayName("[USER-PE-17] 서비스가 SAME_AS_CURRENT_NICKNAME을 던지면 409가 아니라 400과 "
            + "\"현재 닉네임과 다른 닉네임을 사용해 주세요.\"를 반환한다")
    void updateNickname_sameAsCurrentNickname_returns400NotConflict() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.SAME_AS_CURRENT_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("길동"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("현재 닉네임과 다른 닉네임을 사용해 주세요."));
    }

    // ---------- 쿨다운 (USER-PE-40~49) ----------

    @Test
    @DisplayName("[USER-PE-43, 47] 서비스가 BusinessDataException(NICKNAME_CHANGE_COOLDOWN)을 던지면 429와 "
            + "data.nextChangeableAt을 정확히 반환하고, data의 키는 그 하나뿐이다")
    void updateNickname_serviceThrowsCooldown_returns429WithNextChangeableAtDataOnly() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessDataException(ErrorCode.NICKNAME_CHANGE_COOLDOWN,
                new NicknameChangeCooldownResponse("2026-09-16T14:03:21+09:00")))
                .given(userProfileEditService).updateNickname(eq(1L), eq("철수"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"철수\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.NICKNAME_CHANGE_COOLDOWN.getMessage()))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data.nextChangeableAt").value("2026-09-16T14:03:21+09:00"));
    }

    @Test
    @DisplayName("[USER-PE-47] nextChangeableAt이 초=0인 시각이어도 \":00\"이 JSON 직렬화 과정에서 잘리지 않고 "
            + "그대로 응답에 실린다")
    void updateNickname_cooldownDataWithZeroSeconds_preservesTrailingZeroSecondsInJson() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessDataException(ErrorCode.NICKNAME_CHANGE_COOLDOWN,
                new NicknameChangeCooldownResponse("2026-09-16T14:03:00+09:00")))
                .given(userProfileEditService).updateNickname(eq(1L), eq("철수"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"철수\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.data.nextChangeableAt").value("2026-09-16T14:03:00+09:00"));
    }

    @Test
    @DisplayName("[USER-PE-15 확장] 형식 위반 닉네임은 서비스가 쿨다운(429)을 던지도록 스텁해 둬도 400과 형식 "
            + "메시지만 반환한다 — 컨트롤러가 서비스를 아예 호출하지 않아 쿨다운 조회 자체가 일어나지 않는다")
    void updateNickname_formatViolation_neverReachesCooldownCheckEvenIfServiceWouldThrow429() throws Exception {
        String token = authenticatedToken();
        // given: 서비스가 호출됐다면 429를 던지도록 스텁 — 스텁을 타면 테스트가 실패해야 정상인
        // 안전장치(형식 위반 케이스와 동일한 기법을 쿨다운에도 적용).
        willThrow(new BusinessDataException(ErrorCode.NICKNAME_CHANGE_COOLDOWN,
                new NicknameChangeCooldownResponse("2026-09-16T14:03:21+09:00")))
                .given(userProfileEditService).updateNickname(eq(1L), eq("hi!"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"hi!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.nickname").value(NicknamePolicy.PATTERN_MESSAGE));

        verify(userProfileEditService, never()).updateNickname(eq(1L), eq("hi!"));
    }

    @Test
    @DisplayName("[가장 중요한 회귀] BusinessDataException(쿨다운) 응답은 data가 실리고, 같은 컨트롤러의 기존 "
            + "BusinessException(DUPLICATE_NICKNAME 409·SAME_AS_CURRENT_NICKNAME 400·INVALID_CURRENT_PASSWORD 400) "
            + "응답은 여전히 data:null이다 — 핸들러 두 개가 한 요청 안에서 서로를 침범하지 않음을 같은 테스트에서 "
            + "함께 고정한다(비밀번호 경로는 같은 컨트롤러·같은 GlobalExceptionHandler를 공유하는 형제 엔드포인트다)")
    void twoExceptionHandlers_coexistWithoutRegressingExistingDataNullContract() throws Exception {
        String token = authenticatedToken();

        // 1) 신규 경로: BusinessDataException → data가 실린다
        willThrow(new BusinessDataException(ErrorCode.NICKNAME_CHANGE_COOLDOWN,
                new NicknameChangeCooldownResponse("2026-09-16T14:03:21+09:00")))
                .given(userProfileEditService).updateNickname(eq(1L), eq("쿨다운중"));
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"쿨다운중\"}"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.data.nextChangeableAt").value("2026-09-16T14:03:21+09:00"));

        // 2) 기존 경로: BusinessException(DUPLICATE_NICKNAME) → data:null (변함없음)
        willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("중복됨"));
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"중복됨\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").doesNotExist());

        // 3) 기존 경로: BusinessException(SAME_AS_CURRENT_NICKNAME) → data:null (변함없음)
        willThrow(new BusinessException(ErrorCode.SAME_AS_CURRENT_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("길동"));
        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());

        // 4) 형제 엔드포인트(비밀번호 경로)의 기존 BusinessException(INVALID_CURRENT_PASSWORD)도
        // data:null 그대로다 — 같은 GlobalExceptionHandler를 공유하므로 여기서 함께 고정한다.
        willThrow(new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD))
                .given(userProfileEditService).updatePassword(eq(1L), eq("Wrong1234!"), eq("New1234!"));
        mockMvc.perform(patch("/users/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Wrong1234!\",\"newPassword\":\"New1234!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------- 응답에 민감 키가 없다 (USER-PE-6, 39) ----------

    @Test
    @DisplayName("[USER-PE-6, 39] 성공 응답(204)은 본문 자체가 없어 password·uid 키가 존재할 수 없다")
    void updateNickname_successResponse_hasNoBody() throws Exception {
        String token = authenticatedToken();

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"길동gil9\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("[USER-PE-6, 39] 실패 응답(409)의 data는 null이라 password·uid 키를 담을 수 없다")
    void updateNickname_failureResponse_hasNullDataWithNoSensitiveKeys() throws Exception {
        String token = authenticatedToken();
        willThrow(new BusinessException(ErrorCode.DUPLICATE_NICKNAME))
                .given(userProfileEditService).updateNickname(eq(1L), eq("이미있음"));

        mockMvc.perform(patch(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"이미있음\"}"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
