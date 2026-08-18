package com.skhynix.user.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.service.GameService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code GET /games/support}(외부 노출 경로 {@code /api/games/support})를 검증한다. 요구사항:
 * {@code docs/requirements/user/support-team-games.md}(USER-GSP-1 ~ 24).
 *
 * <p>슬라이스 구성은 {@link GameControllerTest}·{@code SupportControllerTest}와 같은 패턴이다 —
 * 실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우고 {@link JwtTokenProvider}·
 * {@link UserAccountRepository}를 목으로 제어한다. 이 슬라이스가 실질적으로 검증하는 핵심은
 * <b>이 경로만 인증 필수라는 것</b>(USER-GSP-12~14, 20 — {@code /games}·{@code /games/lineup}과 정반대)과
 * <b>principal이 서비스 호출의 계정 id로 그대로 전달되는 것</b>이다. 응원 구단 필터링 자체(홈/원정 판정,
 * 반개구간 경계)는 {@code GameService}를 목으로 대체해 이 슬라이스의 검증 대상이 아니다({@code GameServiceTest} 몫).
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/games/support}지만 이
 * 슬라이스에서 호출하는 경로는 {@code /games/support}다.
 */
@WebMvcTest(GameController.class)
@ContextConfiguration(classes = GameController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class GameControllerSupportTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final Long ACCOUNT_ID = 1L;

    private static final Set<String> EXPECTED_KEYS = Set.of(
            "gameId", "stadium", "homeTeam", "homeTeamId", "awayTeam", "awayTeamId",
            "homeTeamScore", "awayTeamScore", "gameDate", "gameState", "cancelReason",
            "inning", "inningHalf");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final GameResponse SAMPLE_GAME = new GameResponse(
            "20260801HTLG02026",
            "광주기아챔피언스필드",
            "KIA",
            6L,
            "LG",
            3L,
            4,
            2,
            LocalDateTime.of(2026, 8, 1, 18, 30, 0),
            "FINISHED",
            null,
            null,
            null);

    /** 유효한 access 토큰을 스텁하고, 그 uid가 활성 계정 {@link #ACCOUNT_ID}로 해석되게 만든다. */
    private String stubAuthenticatedToken() {
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(ACCOUNT_ID, null)));
        return token;
    }

    // ---------- 정상 조회 ----------

    @Test
    @DisplayName("[USER-GSP-1] 유효한 access 토큰으로 date를 주고 요청하면 200과 ApiResponse 래퍼"
            + "(success:true, message:null)에 담긴 경기 배열을 반환한다")
    void getSupportTeamGames_returns200WithApiResponseWrappedArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, LocalDate.of(2026, 8, 1)))
                .willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].gameId").value("20260801HTLG02026"))
                .andExpect(jsonPath("$.data[0].homeTeam").value("KIA"))
                .andExpect(jsonPath("$.data[0].homeTeamId").value(6));
    }

    @Test
    @DisplayName("[USER-GSP-2] 응답 항목의 키 집합은 GET /api/games 항목과 동일한 13개다"
            + "(추가·누락 키 없음)")
    void getSupportTeamGames_responseItemKeySet_matchesGetGamesExactly() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, LocalDate.of(2026, 8, 1)))
                .willReturn(List.of(SAMPLE_GAME));

        // when
        String json = mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // then
        JsonNode item0 = objectMapper.readTree(json).get("data").get(0);
        Set<String> actualKeys = new HashSet<>(item0.propertyNames());
        assertThat(actualKeys).containsExactlyInAnyOrderElementsOf(EXPECTED_KEYS);
    }

    @Test
    @DisplayName("[USER-GSP-11] date가 주어지면 서비스에 그 날짜가 그대로 전달된다(오늘로 흡수되지 않는다)")
    void getSupportTeamGames_dateProvided_passesGivenDateToService() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, LocalDate.of(2026, 8, 9)))
                .willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-09"))
                .andExpect(status().isOk());

        verify(gameService).getSupportTeamGames(ACCOUNT_ID, LocalDate.of(2026, 8, 9));
    }

    @Test
    @DisplayName("date 쿼리 파라미터를 생략하면 서비스에는 null이 그대로 전달된다"
            + "(\"오늘\" 판단은 컨트롤러가 아니라 서비스의 몫 — GET /api/games와 같은 규칙)")
    void getSupportTeamGames_missingDateParameter_passesNullToService() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, null)).willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games/support").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        verify(gameService).getSupportTeamGames(ACCOUNT_ID, null);
    }

    @Test
    @DisplayName("[USER-GSP-19] date가 값 없이(?date=) 전달되면 GET /api/games와 동일하게 null로 "
            + "바인딩돼 서비스에 전달되고 200을 반환한다")
    void getSupportTeamGames_emptyDateValue_bindsToNullSameAsGetGames() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, null)).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", ""))
                .andExpect(status().isOk());

        verify(gameService).getSupportTeamGames(ACCOUNT_ID, null);
    }

    // ---------- 빈 결과(USER-GSP-15/16/17은 서비스가 흡수 — 컨트롤러 관점에서는 동일한 200+빈 배열) ----------

    @Test
    @DisplayName("[USER-GSP-15, USER-GSP-16] 서비스가 빈 리스트를 반환하면 404가 아니라 200과 "
            + "빈 배열({success:true, data:[], message:null})을 반환한다")
    void getSupportTeamGames_emptyResult_returns200WithEmptyArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(gameService.getSupportTeamGames(ACCOUNT_ID, LocalDate.of(2026, 8, 4)))
                .willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // ---------- 형식 오류(USER-GSP-18) ----------

    @Test
    @DisplayName("[USER-GSP-18] 존재하지 않는 날짜(2026-13-01, 13월)면 인증된 요청이어도 400을 반환하고 "
            + "서비스는 호출되지 않는다(ApiResponse 래퍼가 아니다)")
    void getSupportTeamGames_invalidCalendarDate_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-13-01"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("[USER-GSP-18] date가 ISO yyyy-MM-dd 형식이 아니면(20260801) 인증된 요청이어도 400을 "
            + "반환하고 서비스는 호출되지 않는다")
    void getSupportTeamGames_nonIsoFormattedDate_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "20260801"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameService);
    }

    // ---------- 인증(USER-GSP-12~14, 20) ----------

    @Test
    @DisplayName("[USER-GSP-12] Authorization 헤더 없이 요청하면 401과 \"인증이 필요합니다.\"를 "
            + "반환하고 서비스는 호출되지 않는다(GET /api/games가 무인증 200인 것과 정반대)")
    void getSupportTeamGames_withoutAuthorizationHeader_returns401() throws Exception {
        // when & then
        mockMvc.perform(get("/games/support").queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("[USER-GSP-13] 만료·위조된 access 토큰이면 401과 \"인증이 필요합니다.\"를 반환한다"
            + "(200 + 빈 배열이 아니다)")
    void getSupportTeamGames_withInvalidAccessToken_returns401() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer not-a-jwt")
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("[USER-GSP-13] refresh 타입 토큰이면 401과 \"인증이 필요합니다.\"를 반환한다")
    void getSupportTeamGames_withRefreshToken_returns401() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.isRefreshToken("refresh-token")).willReturn(true);

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer refresh-token")
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("[USER-GSP-14] 탈퇴한 계정의 유효기간 남은 access 토큰(uid가 활성 계정을 가리키지 않음)"
            + "이면 401과 \"인증이 필요합니다.\"를 반환한다")
    void getSupportTeamGames_withWithdrawnAccountToken_returns401() throws Exception {
        // given: 토큰 자체는 유효하지만 findActiveAuthByUid가 비어 있다(exit_at is null 조건에서 탈락)
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/games/support")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("[USER-GSP-20] 인증 없이 GET 이외의 메서드(POST)로 요청하면 405가 아니라 401과 "
            + "\"인증이 필요합니다.\"를 반환한다(인증이 메서드 판정보다 앞선다)")
    void postToSupportPath_withoutAuth_returns401() throws Exception {
        // when & then
        mockMvc.perform(post("/games/support").queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }
}
