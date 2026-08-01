package com.skhynix.user.game.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.service.GameService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /games}(외부 노출 경로 {@code /api/member/games})를 검증한다. 계약 문서는 없고
 * (요구사항 문서 없이 구현) 태스크로 확정된 동작 계약을 기준으로 삼는다.
 *
 * <p>슬라이스 구성은 {@link com.skhynix.user.team.controller.TeamControllerTest}와 동일한 패턴이다:
 * {@code @WebMvcTest} + {@code @ContextConfiguration(classes = GameController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다.
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/member/games}지만
 * 이 슬라이스에서 호출하는 경로는 {@code /games}다.
 *
 * <p>실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우므로 permitAll
 * 배선(무인증 200 / 비-GET 401)은 이 슬라이스가 실질적으로 검증하는 핵심이다. 날짜를 반개구간으로 바꿔
 * 리포지토리에 넘기는 로직 자체는 {@code GameService}를 목으로 대체해 이 슬라이스의 검증 대상이
 * 아니다({@code GameServiceTest} 몫).
 */
@WebMvcTest(GameController.class)
@ContextConfiguration(classes = GameController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class GameControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final GameResponse SAMPLE_GAME = new GameResponse(
            "20260801LGHT02026",
            "잠실야구장",
            "LG",
            "KIA",
            3,
            5,
            LocalDateTime.of(2026, 8, 1, 18, 30, 0),
            "FINISHED");

    @Test
    @DisplayName("date를 주면 200과 ApiResponse 래퍼(success:true, message:null)에 담긴 경기 배열을 "
            + "반환하고 각 항목은 GameResponse의 8개 필드를 그대로 담는다")
    void getGames_returns200WithApiResponseWrappedArrayAndFields() throws Exception {
        // given
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].gameId").value("20260801LGHT02026"))
                .andExpect(jsonPath("$.data[0].stadium").value("잠실야구장"))
                .andExpect(jsonPath("$.data[0].homeTeam").value("LG"))
                .andExpect(jsonPath("$.data[0].awayTeam").value("KIA"))
                .andExpect(jsonPath("$.data[0].homeTeamScore").value(3))
                .andExpect(jsonPath("$.data[0].awayTeamScore").value(5))
                .andExpect(jsonPath("$.data[0].gameDate").value("2026-08-01T18:30:00"))
                .andExpect(jsonPath("$.data[0].gameState").value("FINISHED"));
    }

    @Test
    @DisplayName("구장이 미정인 경기(stadium이 null)는 응답 JSON에 \"stadium\": null로 나간다"
            + "(필드 자체가 빠지지 않는다)")
    void getGames_gameWithNullStadium_returnsStadiumFieldAsJsonNull() throws Exception {
        // given
        GameResponse gameWithoutStadium = new GameResponse(
                "20260801LGHT02026",
                null,
                "LG",
                "KIA",
                3,
                5,
                LocalDateTime.of(2026, 8, 1, 18, 30, 0),
                "FINISHED");
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(List.of(gameWithoutStadium));

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stadium").isEmpty())
                .andExpect(jsonPath("$.data[0].gameId").value("20260801LGHT02026"))
                .andExpect(jsonPath("$.data[0].homeTeam").value("LG"));
    }

    @Test
    @DisplayName("서비스가 준 gameDate 오름차순을 컨트롤러가 재배열하지 않고 그대로 응답한다")
    void getGames_returnsServiceOrderUnchanged() throws Exception {
        // given
        GameResponse earlyGame = new GameResponse("20260801OBSK02026", "잠실야구장", "두산", "SSG", 1, 0,
                LocalDateTime.of(2026, 8, 1, 14, 0, 0), "FINISHED");
        GameResponse lateGame = new GameResponse("20260801LGHT02026", "잠실야구장", "LG", "KIA", 3, 5,
                LocalDateTime.of(2026, 8, 1, 18, 30, 0), "IN_PROGRESS");
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(List.of(earlyGame, lateGame));

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gameId").value("20260801OBSK02026"))
                .andExpect(jsonPath("$.data[1].gameId").value("20260801LGHT02026"));
    }

    @Test
    @DisplayName("경기가 없는 날짜를 조회하면 200과 빈 배열({success:true, data:[], message:null})을 "
            + "반환한다")
    void getGames_noGamesOnDate_returns200WithEmptyArray() throws Exception {
        // given
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("date 쿼리 파라미터가 없으면 200을 반환하고 서비스에는 null이 그대로 전달된다"
            + "(\"오늘\" 판단은 컨트롤러가 아니라 서비스의 몫)")
    void getGames_missingDateParameter_returns200AndPassesNullToService() throws Exception {
        // given
        given(gameService.getGames(null)).willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].gameId").value("20260801LGHT02026"));

        verify(gameService).getGames(null);
    }

    @Test
    @DisplayName("date가 존재하지 않는 날짜(2026-13-01, 13월)면 400을 반환하고 서비스는 호출되지 않는다")
    void getGames_invalidCalendarDate_returns400() throws Exception {
        // when & then: 타입 변환 실패는 컨트롤러 진입 전이라 GlobalExceptionHandler가 아니라
        // Spring 기본 DefaultHandlerExceptionResolver가 400으로 처리한다(ApiResponse 래퍼 아님).
        mockMvc.perform(get("/games").queryParam("date", "2026-13-01"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("date가 ISO yyyy-MM-dd 형식이 아니면(20260801) 400을 반환하고 서비스는 호출되지 않는다")
    void getGames_nonIsoFormattedDate_returns400() throws Exception {
        // when & then
        mockMvc.perform(get("/games").queryParam("date", "20260801"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("Authorization 헤더 없이 요청해도 401이 아니라 200과 경기 목록을 반환한다")
    void getGames_withoutAuthorizationHeader_returns200() throws Exception {
        // given: Authorization 헤더를 일부러 붙이지 않는다.
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("만료·위조된 access 토큰이 Authorization 헤더에 담겨 와도 200과 경기 목록을 반환한다"
            + "(헤더 없을 때와 본문 동일)")
    void getGames_withInvalidAccessToken_returns200() throws Exception {
        // given: validateToken이 false면 JwtAuthenticationFilter는 SecurityContext를 건드리지 않고
        // 그대로 통과시킨다(permitAll이라 anyRequest().authenticated()에 걸리지 않음).
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(gameService.getGames(LocalDate.of(2026, 8, 1))).willReturn(List.of(SAMPLE_GAME));

        // when & then
        mockMvc.perform(get("/games").queryParam("date", "2026-08-01")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("경기 목록 경로에 GET 이외의 메서드(POST)로 헤더 없이 요청하면 401과 \"인증이 "
            + "필요합니다.\"를 반환하고 서비스는 호출되지 않는다")
    void postToGamesPath_withoutAuth_returns401() throws Exception {
        // when & then: permitAll이 GET으로만 좁혀져 있어 POST는 anyRequest().authenticated()로 떨어진다.
        mockMvc.perform(post("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameService);
    }
}
