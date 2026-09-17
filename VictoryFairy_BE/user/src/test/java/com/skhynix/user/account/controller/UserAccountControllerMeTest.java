package com.skhynix.user.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.character.dto.EquippedCharacterItemResponse;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.math.BigDecimal;
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

    // 컨트롤러가 프로필 이미지 업로드용 AccountProfileImageService도 생성자로 받아, 없으면 컨텍스트
    // 로딩이 실패한다(이 클래스 테스트는 GET /me만 다뤄 상호작용은 없음).
    @MockitoBean
    private com.skhynix.user.profileimage.service.AccountProfileImageService accountProfileImageService;

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
                List.of(playerOf(100L, "김선수")), 1200L, 340L, null,
                "characters/victory-fairy.svg",
                List.of(new EquippedCharacterItemResponse("의상", "items/cloth/basic.svg")),
                new BigDecimal("0.667"), 5);
    }

    // ---------- 응답 본문 (USER-ME-12 ~ 20) ----------

    @Test
    @DisplayName("[USER-ME-12, 13, 14, 15, 17, 18, 37][USER-PI-65][USER-RK-70, 71] 인증된 사용자가 요청하면 "
            + "200과 ApiResponse에 담긴 프로필을 반환하고, data의 키는 정확히 nickname·supportTeam·"
            + "supportPlayers·point·bqScore·profileImgUrl·characterImgUrl·characterItems·quizAccuracy·"
            + "bqRank 10개뿐이다")
    void getMyProfile_authenticated_returns200WithExactlyTenKeys() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.supportTeam.id").value(6))
                .andExpect(jsonPath("$.data.supportTeam.name").value("KIA"))
                .andExpect(jsonPath("$.data.point").value(1200))
                .andExpect(jsonPath("$.data.bqScore").value(340))
                // 캐릭터·착용 아이템은 EP(BaseURL 없는 오브젝트 키)로 나간다 — profileImgUrl 과 같은 규칙이다.
                .andExpect(jsonPath("$.data.characterImgUrl").value("characters/victory-fairy.svg"))
                .andExpect(jsonPath("$.data.characterItems").isArray())
                .andExpect(jsonPath("$.data.characterItems.length()").value(1))
                .andExpect(jsonPath("$.data.characterItems[0].length()").value(2))
                .andExpect(jsonPath("$.data.characterItems[0].itemType").value("의상"))
                .andExpect(jsonPath("$.data.characterItems[0].imgUrl").value("items/cloth/basic.svg"))
                // USER-ME-41: JSON 숫자로 나가고 문자열 "0.667"이 아니다.
                .andExpect(jsonPath("$.data.quizAccuracy").isNumber())
                .andExpect(jsonPath("$.data.quizAccuracy").value(0.667))
                // USER-RK-71: bqRank는 정수 하나로 나가고 순위 객체를 통째로 싣지 않는다.
                .andExpect(jsonPath("$.data.bqRank").isNumber())
                .andExpect(jsonPath("$.data.bqRank").value(5));
    }

    @Test
    @DisplayName("[USER-ME-32] 응원 선수가 있으면 supportPlayers가 배열로 담기고 각 항목은 "
            + "PlayerResponse의 6개 키(teamId·teamName·playerId·playerName·playerNumber·playerPosition)를 그대로 갖는다")
    void getMyProfile_withSupportPlayers_returnsPlayerResponseShapeArray() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
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
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", new TeamResponse(6L, "KIA"), List.of(), 1200L, 340L,
                        null, "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, 3));

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
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
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
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 0L, 0L, null,
                        "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, null));

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
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 1200L, 0L, null,
                        "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, null));

        // when & then: jsonPath.value(1200)은 숫자 1200과만 매칭되고 문자열 "1200"과는 매칭되지 않는다
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.point").isNumber())
                .andExpect(jsonPath("$.data.point").value(1200));
    }

    @Test
    @DisplayName("[USER-ME-37, 40] 퀴즈를 한 번도 받지 않은 계정도 quizAccuracy 키를 가지며 값은 JSON 숫자 "
            + "0이다(null·문자열·키 누락이 아니다)")
    void getMyProfile_noQuizSubmissions_quizAccuracyKeyIsNumberZero() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 0L, 0L, null,
                        "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, null));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("quizAccuracy")))
                .andExpect(jsonPath("$.data.quizAccuracy").isNumber())
                .andExpect(jsonPath("$.data.quizAccuracy").value(0));
    }

    @Test
    @DisplayName("[USER-ME-41, 42] quizAccuracy는 반올림된 소수 원값 그대로 나가고 할·푼·리·백분율 표기 "
            + "문자열 키가 응답에 없다")
    void getMyProfile_quizAccuracy_isPlainNumberWithoutTextualNotationKeys() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId))
                .willReturn(new UserAccountResponse("nick", null, List.of(), 0L, 0L, null,
                        "characters/victory-fairy.svg", List.of(), new BigDecimal("0.063"), null));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizAccuracy").isNumber())
                .andExpect(jsonPath("$.data.quizAccuracy").value(0.063))
                .andExpect(jsonPath("$.data.quizAccuracyText").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(10));
    }

    @Test
    @DisplayName("[USER-ME-11] ?userId=·?uid= 쿼리 파라미터를 붙여도 무시되고 토큰 주체 본인의 프로필이 그대로 반환된다")
    void getMyProfile_ignoresExtraQueryParams_alwaysReturnsTokenSubjectsProfile() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me?userId=999&uid=someone-elses-uid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("nick"));

        // 컨트롤러가 principal(내부 id) 외의 값을 읽지 않으므로 서비스는 여전히 토큰이 해석한 id로만 호출된다
        verify(userProfileService).getMyProfile(eq(accountId));
    }

    @Test
    @DisplayName("[USER-PI-66, 67] 프로필 이미지가 있는 계정이면 profileImgUrl에 BaseURL 없는 EP가 "
            + "문자 그대로 담긴다")
    void getMyProfile_withProfileImage_returnsEndpointWithoutBaseUrl() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(new UserAccountResponse(
                "nick", new TeamResponse(6L, "KIA"), List.of(), 1200L, 340L,
                "user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg",
                "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, 2));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImgUrl")
                        .value("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg"));
    }

    @Test
    @DisplayName("[USER-PI-65, 66] 프로필 이미지가 없는 계정이면 profileImgUrl 키는 존재하되 값은 null이다"
            + "(빈 문자열도 기본 이미지 URL도 아니다)")
    void getMyProfile_withoutProfileImage_returnsNullNotEmptyString() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("profileImgUrl")))
                .andExpect(jsonPath("$.data.profileImgUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- bqRank (USER-RK-70 ~ 73) ----------

    @Test
    @DisplayName("[USER-RK-70, 71] 활성 응원 구단이 있는 계정이면 bqRank는 정수로 담긴다")
    void getMyProfile_withSupportTeam_bqRankIsInteger() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(new UserAccountResponse(
                "nick", new TeamResponse(6L, "KIA"), List.of(), 1200L, 340L, null,
                "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, 7));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bqRank").isNumber())
                .andExpect(jsonPath("$.data.bqRank").value(7));
    }

    @Test
    @DisplayName("[USER-RK-72, 안전망] 응원 구단이 없는 계정이면 bqRank는 0이나 키 생략이 아니라 null로 담긴다")
    void getMyProfile_noSupportTeam_bqRankIsNull() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(new UserAccountResponse(
                "nick", null, List.of(), 0L, 0L, null,
                "characters/victory-fairy.svg", List.of(), BigDecimal.ZERO, null));

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasKey("bqRank")))
                .andExpect(jsonPath("$.data.bqRank").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("[USER-RK-73] bqRank를 제외한 나머지 9개 키의 이름·값은 이 개정 전후로 동일하다")
    void getMyProfile_bqRankAside_otherNineKeysUnchanged() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, null)));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.supportTeam.id").value(6))
                .andExpect(jsonPath("$.data.supportTeam.name").value("KIA"))
                .andExpect(jsonPath("$.data.supportPlayers.length()").value(1))
                .andExpect(jsonPath("$.data.point").value(1200))
                .andExpect(jsonPath("$.data.bqScore").value(340))
                .andExpect(jsonPath("$.data.characterImgUrl").value("characters/victory-fairy.svg"))
                .andExpect(jsonPath("$.data.characterItems.length()").value(1))
                .andExpect(jsonPath("$.data.quizAccuracy").value(0.667));
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
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(userProfileService);
    }

    // ---------- 토큰 무효화 (USER-ATI-4, 5) ----------

    @Test
    @DisplayName("[USER-ATI-4, USER-ATI-5] 비밀번호 변경 전에 발급된(iat가 기준 시각보다 앞선 초) access "
            + "토큰으로 요청하면 401을 반환하고, 그 응답 본문이 Authorization 헤더 없이 호출한 401 본문과 "
            + "문자 그대로 동일하다(신규 에러 코드·메시지 없음)")
    void getMyProfile_tokenIssuedBeforePasswordChangeBaseline_returns401IdenticalToNoHeader()
            throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        String token = stubValidAccessToken(uid);
        long issuedAt = 1_755_400_000L;
        given(jwtTokenProvider.getIssuedAtEpochSecond(token)).willReturn(issuedAt);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(1L, issuedAt + 1)));

        // when
        var invalidatedResult = mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE))
                .andReturn();
        var noHeaderResult = mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // then: 두 401 응답 본문이 문자 그대로 동일하다
        assertThat(invalidatedResult.getResponse().getContentAsString())
                .isEqualTo(noHeaderResult.getResponse().getContentAsString());
        verifyNoInteractions(userProfileService);
    }

    @Test
    @DisplayName("[USER-ATI-8] 비밀번호 변경 응답으로 방금 받은 access 토큰(iat가 기준 시각과 정확히 같은 초)은 "
            + "즉시 인증된다 — 자기 자신에게 거부되지 않는다")
    void getMyProfile_tokenIssuedExactlyAtBaseline_returns200() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        Long accountId = 1L;
        String token = stubValidAccessToken(uid);
        long issuedAt = 1_755_400_000L;
        given(jwtTokenProvider.getIssuedAtEpochSecond(token)).willReturn(issuedAt);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(accountId, issuedAt)));
        given(userProfileService.getMyProfile(accountId)).willReturn(fullProfile());

        // when & then
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("nick"));
    }
}
