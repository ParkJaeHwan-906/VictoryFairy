package com.skhynix.user.game.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.game.dto.GameLineupResponse;
import com.skhynix.user.game.service.GameLineupService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
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
 * {@code GET /games/lineup}(외부 노출 경로 {@code /api/member/games/lineup})를 검증한다. 계약 출처는
 * {@code docs/requirements/user/game-lineup.md}(USER-GL-1~29)이되, 승인 시점(2026-08-04) 지시로
 * 빈/공백 {@code gameId}는 400이 아니라 404 {@code GAME_NOT_FOUND}로 고정한다(문서 개정 이력 참고).
 *
 * <p>슬라이스 구성은 {@link GameControllerTest}와 동일한 패턴이다: {@code @WebMvcTest} +
 * {@code @ContextConfiguration(classes = GameLineupController.class)}로 {@code UserApplication}의
 * 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다
 * (빠뜨리면 컨텍스트 로딩 자체가 실패한다).
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/member/games/lineup}지만
 * 이 슬라이스에서 호출하는 경로는 {@code /games/lineup}이다.
 *
 * <p>실제 {@link SecurityConfig}를 태우므로 {@code GET /games/lineup} permitAll 배선이 이 슬라이스가
 * 실질적으로 검증하는 핵심이다 — {@code /games} 매처가 정확 경로 매칭이라 이 하위 경로를 커버하지 않는
 * 함정이 이 모듈에 실제로 있었다({@code SecurityConfig} 주석 참고). 조회 로직 자체는
 * {@code GameLineupService}를 목으로 대체해 이 슬라이스의 검증 대상이 아니다({@code GameLineupServiceTest} 몫).
 */
@WebMvcTest(GameLineupController.class)
@ContextConfiguration(classes = GameLineupController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class GameLineupControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameLineupService gameLineupService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final GameLineupResponse SAMPLE_LINEUP = new GameLineupResponse(
            3L,
            List.of(new GameLineupResponse.Pitcher("원태인", "P")),
            List.of(new GameLineupResponse.Batter("김지찬", "CF", 1)));

    @Test
    @DisplayName("[USER-GL-1] 유효한 gameId면 200과 ApiResponse 래퍼에 담긴 팀 그룹 배열을 반환한다")
    void getLineup_validGameId_returns200WithApiResponseWrappedArray() throws Exception {
        // given
        given(gameLineupService.getLineup("20260801LGSS02026")).willReturn(List.of(SAMPLE_LINEUP));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "20260801LGSS02026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("[USER-GL-16, 회귀] Authorization 헤더 없이 요청해도 401이 아니라 200을 반환한다 "
            + "(SecurityConfig의 GET /games/lineup permitAll이 실제로 걸려 있는지 검증)")
    void getLineup_withoutAuthorizationHeader_returns200() throws Exception {
        // given: Authorization 헤더를 일부러 붙이지 않는다.
        given(gameLineupService.getLineup("20260801LGSS02026")).willReturn(List.of(SAMPLE_LINEUP));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "20260801LGSS02026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("[USER-GL-17] 만료·위조된 access 토큰이 담겨 와도 200과 헤더 없을 때와 동일한 응답을 "
            + "반환한다")
    void getLineup_withInvalidAccessToken_returns200() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(gameLineupService.getLineup("20260801LGSS02026")).willReturn(List.of(SAMPLE_LINEUP));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "20260801LGSS02026")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-GL-18] 라인업 경로에 GET 이외의 메서드(POST)로 헤더 없이 요청하면 401과 "
            + "\"인증이 필요합니다.\"를 반환하고 서비스는 호출되지 않는다(405 아님)")
    void postToLineupPath_withoutAuth_returns401() throws Exception {
        // when & then
        mockMvc.perform(post("/games/lineup").queryParam("gameId", "20260801LGSS02026"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameLineupService);
    }

    @Test
    @DisplayName("[USER-GL-19] gameId 파라미터 자체가 없으면 400을 반환하고 서비스는 호출되지 않는다")
    void getLineup_missingGameIdParameter_returns400() throws Exception {
        // when & then: 필수 @RequestParam 누락은 컨트롤러 진입 전 바인딩 단계라 GlobalExceptionHandler를
        // 타지 않는다(ApiResponse 래퍼 여부는 계약이 아니라 상태 코드만 계약).
        mockMvc.perform(get("/games/lineup"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(gameLineupService);
    }

    @Test
    @DisplayName("[USER-GL-20] 없는 경기면 404와 ApiResponse 래퍼(success:false, GAME_NOT_FOUND "
            + "메시지)를 반환한다")
    void getLineup_gameNotFound_returns404WithApiResponseWrapper() throws Exception {
        // given
        given(gameLineupService.getLineup("없는값")).willThrow(new BusinessException(ErrorCode.GAME_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "없는값"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.GAME_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[승인 시점 지시 우선, USER-GL-22 대체] 빈 문자열 gameId(?gameId=)는 400이 아니라 "
            + "404 GAME_NOT_FOUND로 흡수된다")
    void getLineup_blankGameId_returns404NotBadRequest() throws Exception {
        // given: 컨트롤러는 빈 문자열을 그대로 서비스에 넘기고, 서비스가 naverGameId 불일치로 404를 던진다.
        given(gameLineupService.getLineup("")).willThrow(new BusinessException(ErrorCode.GAME_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.GAME_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[USER-GL-21] 경기는 있으나 라인업이 0건이면 200과 빈 배열을 반환한다(404가 아니다)")
    void getLineup_gameExistsButNoLineup_returns200WithEmptyArray() throws Exception {
        // given
        given(gameLineupService.getLineup("20260801LGSS02026")).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "20260801LGSS02026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-GL-9/10/11] 응답 JSON 형상이 고정 계약과 정확히 일치한다: 그룹은 teamId/"
            + "pitchers/batters 3키, 투수는 name/positionName 2키, 타자는 name/positionName/batOrder "
            + "3키이며 playerId는 어디에도 노출되지 않는다")
    void getLineup_jsonShape_matchesFixedContractWithoutPlayerId() throws Exception {
        // given
        given(gameLineupService.getLineup("20260801LGSS02026")).willReturn(List.of(SAMPLE_LINEUP));

        // when & then
        mockMvc.perform(get("/games/lineup").queryParam("gameId", "20260801LGSS02026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].teamId").value(3))
                .andExpect(jsonPath("$.data[0].pitchers[0].name").value("원태인"))
                .andExpect(jsonPath("$.data[0].pitchers[0].positionName").value("P"))
                .andExpect(jsonPath("$.data[0].pitchers[0].playerId").doesNotExist())
                .andExpect(jsonPath("$.data[0].pitchers[0].batOrder").doesNotExist())
                .andExpect(jsonPath("$.data[0].batters[0].name").value("김지찬"))
                .andExpect(jsonPath("$.data[0].batters[0].positionName").value("CF"))
                .andExpect(jsonPath("$.data[0].batters[0].batOrder").value(1))
                .andExpect(jsonPath("$.data[0].batters[0].playerId").doesNotExist());
    }
}
