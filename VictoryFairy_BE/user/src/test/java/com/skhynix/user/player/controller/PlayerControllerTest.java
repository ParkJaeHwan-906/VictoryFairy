package com.skhynix.user.player.controller;

import static org.hamcrest.Matchers.nullValue;
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
 * {@code GET /players}(외부 노출 경로 {@code /api/players})를 검증한다.
 * 요구사항: {@code docs/requirements/user/player-list.md}(USER-PL-1 ~ 16) +
 * {@code docs/requirements/user/player-lookup-team-fallback.md}(USER-PLF-1 ~ 21, PLF-2·3·13은 폐기).
 *
 * <p>슬라이스 구성은 {@link com.skhynix.user.team.controller.TeamControllerTest}와 동일한 패턴이다:
 * {@code @WebMvcTest} + {@code @ContextConfiguration(classes = PlayerController.class)}로
 * {@code UserApplication}의 자동 컨텍스트 병합을 우회하고, {@code SecurityFilterChain} 빈 구성에 필요한
 * {@link JwtTokenProvider}·{@link UserAccountRepository}를 {@code @MockitoBean}으로 함께 등록한다
 * ({@code SupportControllerTest}의 {@code stubAuthenticatedToken()} 패턴을 그대로 가져와 인증 케이스를
 * 만든다). 컨트롤러가 새로 받는 것은 빈이 아니라 {@code @AuthenticationPrincipal Long}이라 추가
 * {@code @MockitoBean}은 필요 없다.
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 외부 경로는 {@code /api/players}지만
 * 이 슬라이스에서 호출하는 경로는 {@code /players}다.
 *
 * <p>실제 {@link SecurityConfig}(따라서 실제 {@code JwtAuthenticationFilter})를 태우므로
 * USER-PL-9/10/12(permitAll 배선)와 USER-PLF-4/5/10/11/15(인증 유무·토큰 형식에 따른 분기)는 이 슬라이스가
 * 실질적으로 검증하는 핵심이다. 반대로 정렬·필터링 자체, 그리고 적용 구단 결정 로직(USER-PL-3/5/13/14,
 * USER-PLF-1/6/7/8/17/18/19/21)은 {@code PlayerService}를 목으로 대체해 검증 대상이 아니다
 * (DB·응원 구단 리포지토리 몫 — {@code PlayerServiceTest}가 담당). 여기서 고정하는 것은
 * <b>쿼리 파라미터·인증 상태가 서비스 인자로 어떻게 넘어가는가</b>와 <b>HTTP 상태 코드·응답 형태</b>다.
 */
@WebMvcTest(PlayerController.class)
@ContextConfiguration(classes = PlayerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PlayerControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final Long ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerService playerService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static final List<PlayerResponse> THREE_PLAYERS = List.of(
            responseOf(1L, "강백호"),
            responseOf(2L, "김도영"),
            responseOf(3L, "이정후"));

    /**
     * 서비스가 이미 만들어 준 DTO를 흉내내는 스텁 값. 이 슬라이스에서 검증하는 것은 직렬화된 키·값이지
     * 엔티티→DTO 매핑이 아니므로(그건 {@code PlayerServiceTest} 몫) 구단은 KIA 하나로 고정한다.
     */
    private static PlayerResponse responseOf(Long playerId, String playerName) {
        return new PlayerResponse(6L, "KIA", playerId, playerName, "1" + playerId, "INFIELDER");
    }

    /** 유효한 access 토큰을 스텁하고, 그 uid 가 활성 계정 {@link #ACCOUNT_ID} 로 해석되게 만든다. */
    private String stubAuthenticatedToken() {
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.of(ACCOUNT_ID));
        return token;
    }

    @Test
    @DisplayName("[USER-PL-1] 선수 목록을 요청하면 200과 ApiResponse 래퍼(success:true, message:null)에 "
            + "담긴 선수 배열을 반환한다")
    void getPlayers_returns200WithApiResponseWrappedArray() throws Exception {
        // given
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-2] 선수 항목은 teamId·teamName·playerId·playerName·playerNumber·"
            + "playerPosition 여섯 필드만 담고 average·kboPlayerId는 응답 어디에도 없다")
    void getPlayers_itemContainsTeamAndPlayerFieldsOnly() throws Exception {
        // given
        given(playerService.getPlayers(null, null, null))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].length()").value(6))
                .andExpect(jsonPath("$.data[0].teamId").value(6))
                .andExpect(jsonPath("$.data[0].teamName").value("KIA"))
                .andExpect(jsonPath("$.data[0].playerId").value(2))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"))
                .andExpect(jsonPath("$.data[0].playerNumber").value("12"))
                .andExpect(jsonPath("$.data[0].playerPosition").value("INFIELDER"))
                .andExpect(jsonPath("$.data[0].average").doesNotExist())
                .andExpect(jsonPath("$.data[0].kboPlayerId").doesNotExist())
                // 구단은 중첩 객체가 아니라 평평한 두 필드다 — 프론트 계약이 바뀌면 여기가 먼저 깨진다
                .andExpect(jsonPath("$.data[0].team").doesNotExist())
                .andExpect(jsonPath("$.data[0].name").doesNotExist())
                .andExpect(jsonPath("$.data[0].id").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-2] 등번호·포지션이 없는 선수는 키가 사라지지 않고 playerNumber·playerPosition이 "
            + "null로 직렬화된다(Jackson NON_NULL 설정이 붙으면 깨질 자리)")
    void getPlayers_missingNumberAndPosition_serializedAsExplicitNulls() throws Exception {
        // given
        given(playerService.getPlayers(null, null, null))
                .willReturn(List.of(new PlayerResponse(6L, "KIA", 5L, "무명", null, null)));

        // when & then
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].length()").value(6))
                .andExpect(jsonPath("$.data[0].playerNumber").value(nullValue()))
                .andExpect(jsonPath("$.data[0].playerPosition").value(nullValue()));
    }

    @Test
    @DisplayName("[USER-PL-5] teamId 쿼리 파라미터를 주면 그 값을 서비스에 그대로 넘기고 필터된 목록을 "
            + "반환한다")
    void getPlayers_withTeamId_passesParameterToService() throws Exception {
        // given
        given(playerService.getPlayers(null, 6L, null))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"));
    }

    @Test
    @DisplayName("[USER-PL-4] teamId를 생략하면 서비스에 null이 넘어가 전체 목록을 반환한다")
    void getPlayers_withoutTeamId_passesNullToService() throws Exception {
        // given: teamId=null 스텁만 두었으므로, 컨트롤러가 다른 값을 넘기면 빈 목록이 나와 단언이 깨진다
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

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
        given(playerService.getPlayers(null, 999L, null)).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-7, USER-PLF-11 기본형] teamId가 숫자가 아니면 400을 반환하고 서비스는 호출되지 "
            + "않는다(비인증)")
    void getPlayers_nonNumericTeamId_returns400() throws Exception {
        // when & then: 타입 변환 실패는 컨트롤러 진입 전이라 GlobalExceptionHandler가 아니라
        // Spring 기본 DefaultHandlerExceptionResolver가 400으로 처리한다(ApiResponse 래퍼 아님).
        mockMvc.perform(get("/players").queryParam("teamId", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(playerService);
    }

    @Test
    @DisplayName("[USER-PLF-11] 응원 구단이 있는 계정의 유효한 토큰을 실어도 teamId가 숫자가 아니면 여전히 "
            + "400이다(오버라이딩 판단에 도달하지 못한다) — 응원 구단 조회·선수 조회 모두 미수행")
    void getPlayers_nonNumericTeamId_withAuthenticatedToken_stillReturns400WithoutServiceCall()
            throws Exception {
        // given: 토큰 자체는 유효하지만 타입 변환이 컨트롤러 진입 전에 실패해 principal 해석까지 못 간다
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(get("/players")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("teamId", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(playerService);
    }

    @Test
    @DisplayName("[USER-PL-8] page/size 쿼리 파라미터를 붙여도 무시하고 전체 목록을 배열로 반환하며 "
            + "content/totalElements 같은 페이지 필드가 없다")
    void getPlayers_ignoresPagingParameters_returnsFullArray() throws Exception {
        // given
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").queryParam("page", "1").queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-9, USER-PLF-4, USER-PLF-15] Authorization 헤더 없이 요청해도 401이 아니라 200과 "
            + "선수 목록을 반환한다")
    void getPlayers_withoutAuthorizationHeader_returns200() throws Exception {
        // given
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then: Authorization 헤더를 일부러 붙이지 않는다.
        mockMvc.perform(get("/players"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-10, USER-PLF-5] 만료·위조된 access 토큰이 Authorization 헤더에 담겨 와도 200과 "
            + "선수 목록을 반환한다(헤더 없을 때와 본문 동일)")
    void getPlayers_withInvalidAccessToken_returns200() throws Exception {
        // given: validateToken이 false면 JwtAuthenticationFilter는 SecurityContext를 건드리지 않고
        // 그대로 통과시킨다(permitAll이라 anyRequest().authenticated()에 걸리지 않음).
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PLF-5] 무효 토큰이어도 teamId가 실려 있으면 (비인증과 동일하게) 그 teamId로 필터한다")
    void getPlayers_withInvalidAccessTokenAndTeamId_appliesRequestedTeamId() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);
        given(playerService.getPlayers(null, 9L, null))
                .willReturn(List.of(responseOf(4L, "박건우")));

        // when & then
        mockMvc.perform(get("/players")
                        .header("Authorization", "Bearer not-a-jwt")
                        .queryParam("teamId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].playerName").value("박건우"));
    }

    @Test
    @DisplayName("[USER-PLF-5] refresh 타입 토큰으로 요청해도 401이 아니라 200과 전 구단 목록을 반환한다")
    void getPlayers_withRefreshTypeToken_returns200WithAllPlayers() throws Exception {
        // given: JwtAuthenticationFilter는 validateToken && !isRefreshToken일 때만 uid를 해석한다
        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.isRefreshToken("refresh-token")).willReturn(true);
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").header("Authorization", "Bearer refresh-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        verifyNoInteractions(userAccountRepository);
    }

    @Test
    @DisplayName("[USER-PLF-5] 유효기간이 남았지만 탈퇴한 계정의 access 토큰으로 요청해도 401이 아니라 200과 "
            + "전 구단 목록을 반환한다(findActiveIdByUid가 비어 있어 principal이 채워지지 않는다)")
    void getPlayers_withWithdrawnAccountToken_returns200WithAllPlayers() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveIdByUid(uid)).willReturn(Optional.empty());
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PLF-1] 유효한 access 토큰으로 요청하면 필터가 해석한 계정 id를 서비스에 그대로 "
            + "넘긴다")
    void getPlayers_withAuthenticatedToken_passesResolvedAccountIdToService() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(playerService.getPlayers(ACCOUNT_ID, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PLF-14] 응원 구단이 teamId를 오버라이딩한 응답도 항목 키가 평시와 동일한 여섯 "
            + "필드이고, 적용 구단·오버라이딩 여부를 알리는 필드가 없다")
    void getPlayers_overriddenResponse_keepsResponseShapeUnchanged() throws Exception {
        // given: 컨트롤러 관점에서는 서비스가 이미 오버라이딩을 반영한 결과를 준다 — teamId=9를 보냈지만
        // 서비스 스텁은 (ACCOUNT_ID, 9, null)로 걸어 응원 구단으로 대체된 상황을 표현한다
        String token = stubAuthenticatedToken();
        given(playerService.getPlayers(ACCOUNT_ID, 9L, null))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("teamId", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].length()").value(6))
                .andExpect(jsonPath("$.data[0].playerId").value(2))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"))
                .andExpect(jsonPath("$.data[0].appliedTeamId").doesNotExist())
                .andExpect(jsonPath("$.data[0].team").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PLF-10] teamId를 값 없이(?teamId=) 붙이면 400이 아니라 teamId 없음으로 처리돼 "
            + "전 구단 목록을 반환한다(비인증)")
    void getPlayers_emptyTeamIdParameter_unauthenticated_treatsAsAbsent() throws Exception {
        // given: Spring의 StringToNumberConverterFactory가 빈 문자열을 null로 변환한다
        given(playerService.getPlayers(null, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PLF-10] 인증된 요청에서도 teamId를 값 없이(?teamId=) 붙이면 400이 아니라 "
            + "teamId 없음으로 서비스에 전달된다")
    void getPlayers_emptyTeamIdParameter_authenticated_passesNullToService() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(playerService.getPlayers(ACCOUNT_ID, null, null)).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("teamId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-13] name 쿼리 파라미터를 주면 그 값을 서비스에 그대로 넘기고 부분일치 결과를 "
            + "반환한다")
    void getPlayers_withName_passesParameterToService() throws Exception {
        // given: (null, null, "도영") 스텁만 두었으므로 컨트롤러가 다른 값을 넘기면 빈 목록이 나와 단언이 깨진다
        given(playerService.getPlayers(null, null, "도영"))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players").queryParam("name", "도영"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"));
    }

    @Test
    @DisplayName("[USER-PL-14] teamId와 name을 함께 주면 두 값을 모두 서비스에 넘긴다")
    void getPlayers_withTeamIdAndName_passesBothToService() throws Exception {
        // given
        given(playerService.getPlayers(null, 6L, "도영"))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(get("/players").queryParam("teamId", "6").queryParam("name", "도영"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"));
    }

    @Test
    @DisplayName("[USER-PL-15] name을 값 없이(?name=) 붙이면 컨트롤러는 null이 아니라 빈 문자열을 넘긴다"
            + "(검색어 없음으로 접는 것은 서비스 몫)")
    void getPlayers_emptyNameParameter_passesEmptyStringToService() throws Exception {
        // given: 컨트롤러 레벨의 실제 동작을 계약으로 고정한다 — 이 스텁이 (null, null, null)이면 테스트가 깨진다
        given(playerService.getPlayers(null, null, "")).willReturn(THREE_PLAYERS);

        // when & then
        mockMvc.perform(get("/players").queryParam("name", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("[USER-PL-16] 일치하는 선수가 없는 name으로 요청하면 404가 아니라 200과 빈 배열을 반환한다")
    void getPlayers_noNameMatch_returns200WithEmptyArray() throws Exception {
        // given
        given(playerService.getPlayers(null, null, "없는이름")).willReturn(Collections.emptyList());

        // when & then
        mockMvc.perform(get("/players").queryParam("name", "없는이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("[USER-PL-12, USER-PLF-15] 선수 목록 경로에 GET 이외의 메서드(POST)로 헤더 없이 요청하면 "
            + "401과 \"인증이 필요합니다.\"를 반환하고 서비스는 호출되지 않는다")
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
