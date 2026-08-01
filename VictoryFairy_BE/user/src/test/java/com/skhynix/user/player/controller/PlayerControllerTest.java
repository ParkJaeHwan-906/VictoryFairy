package com.skhynix.user.player.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.player.service.PlayerService;
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
 * {@code GET /players}(외부 노출 경로 {@code /api/member/players})를 검증한다.
 * 요구사항: {@code docs/requirements/user/player-list.md}(USER-PL-1 ~ 12).
 *
 * <p>슬라이스 구성은 {@link com.skhynix.user.team.controller.TeamControllerTest}와 동일한 패턴이다:
 * {@code @WebMvcTest} + {@code @ContextConfiguration(classes = PlayerController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다.
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/member/players}지만
 * 이 슬라이스에서 호출하는 경로는 {@code /players}다.
 *
 * <p>실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우므로
 * USER-PL-9/10/12(permitAll 배선)은 이 슬라이스가 실질적으로 검증하는 핵심이다. 반대로 정렬·필터링 자체
 * (USER-PL-3/5)는 {@code PlayerService}를 목으로 대체해 검증 대상이 아니다(DB 몫 — 문서의 "미커버 영역"
 * 참조).
 */
@WebMvcTest(PlayerController.class)
@ContextConfiguration(classes = PlayerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PlayerControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerService playerService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final List<PlayerResponse> THREE_PLAYERS = List.of(
            new PlayerResponse(1L, "강백호"),
            new PlayerResponse(2L, "김도영"),
            new PlayerResponse(3L, "이정후"));

    @Test
    @DisplayName("[USER-PL-1] 선수 목록을 요청하면 200과 ApiResponse 래퍼(success:true, message:null)에 "
            + "담긴 선수 배열을 반환한다")
    void getPlayers_returns200WithApiResponseWrappedArray() throws Exception {
        // given
        given(playerService.getPlayers(null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-2] 선수 항목은 id·name 두 필드만 담고 average·kboPlayerId·team은 "
            + "응답 어디에도 없다")
    void getPlayers_itemContainsOnlyIdAndName() throws Exception {
        // given
        given(playerService.getPlayers(null)).willReturn(List.of(new PlayerResponse(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(2))
                .andExpect(jsonPath("$.data[0].name").value("김도영"))
                .andExpect(jsonPath("$.data[0].average").doesNotExist())
                .andExpect(jsonPath("$.data[0].kboPlayerId").doesNotExist())
                .andExpect(jsonPath("$.data[0].team").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-5] teamId 쿼리 파라미터를 주면 그 값을 서비스에 그대로 넘기고 필터된 목록을 "
            + "반환한다")
    void getPlayers_withTeamId_passesParameterToService() throws Exception {
        // given
        given(playerService.getPlayers(6L)).willReturn(List.of(new PlayerResponse(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("김도영"));
    }

    @Test
    @DisplayName("[USER-PL-4] teamId를 생략하면 서비스에 null이 넘어가 전체 목록을 반환한다")
    void getPlayers_withoutTeamId_passesNullToService() throws Exception {
        // given: teamId=null 스텁만 두었으므로, 컨트롤러가 다른 값을 넘기면 빈 목록이 나와 단언이 깨진다
        given(playerService.getPlayers(null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-6, USER-PL-11] 선수가 없는 teamId로 요청하면 404가 아니라 200과 빈 배열을 "
            + "반환한다")
    void getPlayers_unknownTeamId_returns200WithEmptyArray() throws Exception {
        // given
        given(playerService.getPlayers(999L)).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-7] teamId가 숫자가 아니면 400을 반환하고 서비스는 호출되지 않는다")
    void getPlayers_nonNumericTeamId_returns400() throws Exception {
        // when & then: 타입 변환 실패는 컨트롤러 진입 전이라 GlobalExceptionHandler가 아니라
        // Spring 기본 DefaultHandlerExceptionResolver가 400으로 처리한다(ApiResponse 래퍼 아님).
        mockMvc.perform(get("/players").queryParam("teamId", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(playerService);
    }

    @Test
    @DisplayName("[USER-PL-8] page/size 쿼리 파라미터를 붙여도 무시하고 전체 목록을 배열로 반환하며 "
            + "content/totalElements 같은 페이지 필드가 없다")
    void getPlayers_ignoresPagingParameters_returnsFullArray() throws Exception {
        // given
        given(playerService.getPlayers(null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").queryParam("page", "1").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-9] Authorization 헤더 없이 요청해도 401이 아니라 200과 선수 목록을 반환한다")
    void getPlayers_withoutAuthorizationHeader_returns200() throws Exception {
        // given
        given(playerService.getPlayers(null)).willReturn(THREE_PLAYERS);

        // when & then: Authorization 헤더를 일부러 붙이지 않는다.
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-10] 만료·위조된 access 토큰이 Authorization 헤더에 담겨 와도 200과 선수 목록을 "
            + "반환한다"
            + "(헤더 없을 때와 본문 동일)")
    void getPlayers_withInvalidAccessToken_returns200() throws Exception {
        // given: validateToken이 false면 JwtAuthenticationFilter는 SecurityContext를 건드리지 않고
        // 그대로 통과시킨다(permitAll이라 anyRequest().authenticated()에 걸리지 않음).
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(playerService.getPlayers(null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-12] 선수 목록 경로에 GET 이외의 메서드(POST)로 헤더 없이 요청하면 401과 "
            + "\"인증이 필요합니다.\"를 반환하고 서비스는 호출되지 않는다")
    void postToPlayersPath_withoutAuth_returns401() throws Exception {
        // when & then: permitAll이 GET으로만 좁혀져 있어 POST는 anyRequest().authenticated()로 떨어진다.
        mockMvc.perform(post("/players"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(playerService);
    }
}
