package com.skhynix.user.account.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.util.List;
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
 * {@code GET /users/me}(외부 노출 경로 {@code /api/users/me})를 검증한다.
 * 요구사항: {@code docs/requirements/user/me-profile.md}(USER-ME-7 ~ 20).
 *
 * <p>슬라이스 구성은 {@code UserAccountControllerTest}(탈퇴)와 동일한 패턴을 따른다 —
 * {@code @WebMvcTest} + {@code @ContextConfiguration(classes = UserAccountController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다.
 * 컨트롤러가 {@link UserAccountService}도 생성자로 받으므로(탈퇴), 이 슬라이스도 함께 목으로 등록해야
 * 컨텍스트가 로딩된다(이 파일의 테스트는 그 빈과 상호작용하지 않는다).
 *
 * <p><b>이 슬라이스가 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를
 * 태우므로</b>, USER-ME-7~10의 401 판정을 서비스 목이 아니라 필터 레벨에서 검증한다.
 *
 * <p><b>범위 밖</b>: USER-ME-21(지연 로딩 초기화)·USER-ME-22(SELECT ≤ 4회)는 {@code UserProfileService}를
 * 목으로 대체하는 이 슬라이스로는 증명할 수 없다 — 실제 JPA·Hibernate 세션이 필요하다.
 */
@WebMvcTest(UserAccountController.class)
@ContextConfiguration(classes = UserAccountController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class UserAccountControllerMeTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private UserAccountService userAccountService;

    // 컨트롤러가 닉네임·비밀번호 수정용 UserProfileEditService도 생성자로 받아, 없으면 컨텍스트
    // 로딩이 실패한다(이 클래스 테스트는 GET /me만 다뤄 상호작용은 없음).
    @MockitoBean
    private UserProfileEditService userProfileEditService;

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

    private static PlayerResponse playerOf(Long playerId, String playerName) {
        return new PlayerResponse(6L, "KIA", playerId, playerName, "10", "INFIELDER");
    }

    private static UserAccountResponse fullProfile() {
        return new UserAccountResponse("nick", new TeamResponse(6L, "KIA"),
                List.of(playerOf(100L, "김선수")), 1200L, 340L);
    }

    // ---------- 응답 본문 (USER-ME-12 ~ 20) ----------

    @Test
    @DisplayName("[USER-ME-12, 13, 14, 15, 17, 18] 인증된 사용자가 요청하면 200과 ApiResponse에 담긴 "
            + "프로필을 반환하고, data의 키는 정확히 nickname·supportTeam·supportPlayers·point·bqScore 5개뿐이다")
    void getMyProfile_authenticated_returns200WithExactlyFiveKeys() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.supportTeam.id").value(6))
                .andExpect(jsonPath("$.data.supportTeam.name").value("KIA"))
                .andExpect(jsonPath("$.data.point").value(1200))
                .andExpect(jsonPath("$.data.bqScore").value(340));
    }

    @Test
    @DisplayName("[USER-ME-32] 응원 선수가 있으면 supportPlayers가 배열로 담기고 각 항목은 "
            + "PlayerResponse의 6개 키(teamId·teamName·playerId·playerName·playerNumber·playerPosition)를 그대로 갖는다")
    void getMyProfile_withSupportPlayers_returnsPlayerResponseShapeArray() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportPlayers").isArray())
                .andExpect(jsonPath("$.data.supportPlayers.length()").value(1))
                .andExpect(jsonPath("$.data.supportPlayers[0].length()").value(6))
                .andExpect(jsonPath("$.data.supportPlayers[0].teamId").value(6))
                .andExpect(jsonPath("$.data.supportPlayers[0].teamName").value("KIA"))
                .andExpect(jsonPath("$.data.supportPlayers[0].playerId").value(100))
                .andExpect(jsonPath("$.data.supportPlayers[0].playerName").value("김선수"))
                .andExpect(jsonPath("$.data.supportPlayers[0].playerNumber").value("10"))
                .andExpect(jsonPath("$.data.supportPlayers[0].playerPosition").value("INFIELDER"));
    }

    @Test
    @DisplayName("[USER-ME-34] 응원 선수가 없으면 supportPlayers는 null이 아니라 빈 배열로 담긴다")
    void getMyProfile_noSupportPlayers_returnsEmptyArrayNotNull() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", new TeamResponse(6L, "KIA"), List.of(), 1200L, 340L));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportPlayers").isArray())
                .andExpect(jsonPath("$.data.supportPlayers.length()").value(0));
    }

    @Test
    @DisplayName("[USER-ME-13] 응답 어디에도 id·uid·password·email·tel·exitAt·createdAt·updatedAt 키가 없다")
    void getMyProfile_response_neverContainsEntityInternalKeys() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.uid").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.tel").doesNotExist())
                .andExpect(jsonPath("$.data.exitAt").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("[USER-ME-16, 안전망] 응원 구단이 없는 프로필이면 supportTeam이 null인 채로 200을 반환한다")
    void getMyProfile_noSupportTeam_returns200WithNullSupportTeam() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 0L, 0L));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.supportTeam").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("[USER-ME-17] point는 JSON 숫자로 담긴다(문자열이 아니다)")
    void getMyProfile_point_isJsonNumberNotString() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 1200L, 0L));

        // when & then: jsonPath.value(1200)은 숫자 1200과만 매칭되고 문자열 "1200"과는 매칭되지 않는다
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.point").isNumber())
                .andExpect(jsonPath("$.data.point").value(1200));
    }

    @Test
    @DisplayName("[USER-ME-11] ?userId=·?uid= 쿼리 파라미터를 붙여도 무시되고 토큰 주체 본인의 프로필이 그대로 반환된다")
    void getMyProfile_ignoresExtraQueryParams_alwaysReturnsTokenSubjectsProfile() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(accountId));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me?userId=999&uid=someone-elses-uid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("nick"));

        // 컨트롤러가 principal(내부 id) 외의 값을 읽지 않으므로 서비스는 여전히 토큰이 해석한 id로만 호출된다
        verify(userProfileService).getMyProfile(eq(accountId));
    }

    // ---------- 인증 (USER-ME-7 ~ 10) ----------

    @Test
    @DisplayName("[USER-ME-7] Authorization 헤더 없이 요청하면 401과 \"인증이 필요합니다.\" 바디를 반환하고 "
            + "서비스는 호출되지 않는다")
    void getMyProfile_noAuthorizationHeader_returns401AndDoesNotCallService() throws Exception {
        // when & then
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileService);
    }

    @Test
    @DisplayName("[USER-ME-8] 위조·만료된(검증 실패) access 토큰으로 요청하면 401을 반환하고 서비스는 호출되지 않는다")
    void getMyProfile_forgedOrExpiredToken_returns401AndDoesNotCallService() throws Exception {
        // given: jwtTokenProvider가 기본적으로(스텁 없이) validateToken=false를 반환하는 목이므로
        // 위조·만료 토큰과 동일한 상황이다.
        String token = "not-a-jwt";

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileService);
    }

    @Test
    @DisplayName("[USER-ME-9] refresh 토큰으로 요청하면 401을 반환하고 서비스는 호출되지 않는다")
    void getMyProfile_refreshToken_returns401AndDoesNotCallService() throws Exception {
        // given
        String token = "refresh-token";
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(true);

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileService);
    }

    @Test
    @DisplayName("[USER-ME-10] 탈퇴한 계정의 access 토큰(uid가 더 이상 활성 계정을 가리키지 않음)으로 요청하면 "
            + "401을 반환하고 서비스는 호출되지 않는다(프로필이 반환되지 않는다)")
    void getMyProfile_withdrawnAccountToken_returns401AndDoesNotCallService() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileService);
    }
}
