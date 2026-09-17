package com.skhynix.user.support.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.support.service.SupportService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /support/**}(외부 노출 경로 {@code /api/support/**}) 3개 엔드포인트를 검증한다.
 *
 * <p>슬라이스 구성은 {@code UserAccountControllerTest} 와 같은 패턴이다 — 실제 {@link SecurityConfig}(따라서
 * 실제 {@code JwtAuthenticationFilter})를 태우고 {@link JwtTokenProvider}·{@link UserAccountRepository} 를
 * 목으로 제어해 "토큰이 해석된 내부 id 로 서비스가 호출된다"까지 확인한다. 이 슬라이스가 실질적으로
 * 검증하는 핵심은 <b>USER-SP-1(인증 필수)</b> 과 <b>USER-SP-2(대상 계정은 토큰에서만 온다)</b> 다 —
 * {@code SecurityConfig} 에 실수로 {@code permitAll} 을 추가하면 여기서 깨진다.
 *
 * <p><b>MockMvc 는 context-path 를 적용하지 않는다</b> — 외부 경로는 {@code /api/support/**} 지만
 * 여기서 호출하는 경로는 {@code /support/**} 다.
 *
 * <p>요청 본문은 {@code ObjectMapper} 를 거치지 않고 원시 JSON 문자열로 쓴다(Jackson 3 전환 이후
 * {@code tools.jackson.databind} 패키지를 테스트에서 다시 끌어오지 않기 위함).
 */
@WebMvcTest(SupportController.class)
@ContextConfiguration(classes = SupportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SupportControllerTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final Long ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupportService supportService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    /**
     * 서비스가 이미 만들어 준 DTO를 흉내내는 스텁 값. 이 슬라이스는 직렬화된 키·값만 보므로 구단은
     * KIA 하나로 고정한다(엔티티→DTO 매핑은 {@code SupportServiceTest} 몫).
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
        given(userAccountRepository.findActiveAuthByUid(uid))
                .willReturn(Optional.of(new ActiveAccountView(ACCOUNT_ID, null)));
        return token;
    }

    // ---------- 응원 구단 선택 ----------

    @Test
    @DisplayName("[USER-SP-13, USER-SP-2] 구단 선택이 성공하면 200과 ApiResponse에 담긴 현재 응원 구단을 반환하고, "
            + "서비스는 토큰에서 해석된 내부 id로 호출된다")
    void selectTeam_returns200AndDelegatesWithResolvedAccountId() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.selectTeam(ACCOUNT_ID, 6L)).willReturn(new TeamResponse(6L, "KIA"));

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(6))
                .andExpect(jsonPath("$.data.name").value("KIA"))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(supportService).selectTeam(ACCOUNT_ID, 6L);
    }

    @Test
    @DisplayName("[USER-SP-2] 본문에 다른 계정 id를 실어도 서버는 그 값을 쓰지 않고 토큰에서 해석한 id로만 처리한다")
    void selectTeam_ignoresAccountIdInBody() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.selectTeam(ACCOUNT_ID, 6L)).willReturn(new TeamResponse(6L, "KIA"));

        // when & then: userAccountId 는 요청 DTO 에 필드 자체가 없어 조용히 무시된다
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6,\"userAccountId\":9999}"))
                .andExpect(status().isOk());

        verify(supportService).selectTeam(ACCOUNT_ID, 6L);
    }

    @Test
    @DisplayName("[USER-SP-4] teamId가 없으면 400과 필드 오류를 반환하고 서비스는 호출되지 않는다")
    void selectTeam_missingTeamId_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.teamId").exists());

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-5] 서비스가 TEAM_NOT_FOUND를 던지면 404와 \"존재하지 않는 구단입니다.\"를 반환한다")
    void selectTeam_teamNotFound_returns404() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.selectTeam(ACCOUNT_ID, 999L))
                .willThrow(new BusinessException(ErrorCode.TEAM_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("존재하지 않는 구단입니다."));
    }

    // ---------- 응원 선수 추가 ----------

    @Test
    @DisplayName("[USER-SP-23] 선수 추가가 성공하면 200과 현재 응원 중인 선수 전체 목록을 반환한다")
    void addPlayers_returns200WithAllCurrentlySupported() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of(3L)))
                .willReturn(List.of(responseOf(2L, "김도영"), responseOf(3L, "양현종")));

        // when & then: 요청은 3번 하나뿐이지만 응답은 기존 2번까지 포함한다(추가 방식)
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].playerName").value("김도영"))
                .andExpect(jsonPath("$.data[1].playerName").value("양현종"));

        verify(supportService).addPlayers(ACCOUNT_ID, List.of(3L));
    }

    @Test
    @DisplayName("[USER-SP-30, USER-SP-32] 4개짜리 일괄 본문으로 선수를 추가하면 200과 4건짜리 응답 배열을 반환한다"
            + "(상한과 같은 크기의 정상 일괄 경로)")
    void addPlayers_batchOfFourAtLimit_returns200WithFourItems() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        List<Long> requestIds = List.of(2L, 3L, 4L, 5L);
        given(supportService.addPlayers(ACCOUNT_ID, requestIds)).willReturn(List.of(
                responseOf(2L, "선수2"), responseOf(3L, "선수3"),
                responseOf(4L, "선수4"), responseOf(5L, "선수5")));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[2,3,4,5]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(4));

        verify(supportService).addPlayers(ACCOUNT_ID, requestIds);
    }

    @Test
    @DisplayName("[USER-SP-21] playerIds가 빈 배열이어도 400이 아니라 200이다")
    void addPlayers_emptyArray_returns200() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of())).willReturn(List.of());

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("[USER-SP-21] playerIds 필드 자체가 없으면(null) 400이다 — 빈 배열과 구분한다")
    void addPlayers_nullPlayerIds_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.playerIds").exists());

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-15] 서비스가 SUPPORT_TEAM_REQUIRED를 던지면 400과 안내 메시지를 반환한다")
    void addPlayers_withoutSupportTeam_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of(2L)))
                .willThrow(new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("응원하는 구단을 먼저 선택해 주세요."));
    }

    @Test
    @DisplayName("[USER-SP-17] 서비스가 PLAYER_NOT_IN_SUPPORT_TEAM을 던지면 400과 안내 메시지를 반환한다")
    void addPlayers_playerOfAnotherTeam_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of(20L)))
                .willThrow(new BusinessException(ErrorCode.PLAYER_NOT_IN_SUPPORT_TEAM));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[20]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("응원하는 구단 소속 선수만 선택할 수 있습니다."));
    }

    @Test
    @DisplayName("[USER-SP-33] 서비스가 SUPPORT_PLAYER_LIMIT_EXCEEDED를 던지면 400과 상한 안내 메시지를 반환한다")
    void addPlayers_limitExceeded_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of(9L)))
                .willThrow(new BusinessException(ErrorCode.SUPPORT_PLAYER_LIMIT_EXCEEDED));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[9]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("응원 선수는 최대 4명까지 선택할 수 있습니다."));
    }

    @Test
    @DisplayName("[USER-SP-33] 5개짜리 일괄 본문으로 선수를 추가하면 400과 상한 안내 메시지를 반환한다"
            + "(프론트가 실제로 보내는 일괄 추가 형태)")
    void addPlayers_batchOfFiveExceedsLimit_returns400() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        List<Long> requestIds = List.of(1L, 2L, 3L, 4L, 5L);
        given(supportService.addPlayers(ACCOUNT_ID, requestIds))
                .willThrow(new BusinessException(ErrorCode.SUPPORT_PLAYER_LIMIT_EXCEEDED));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[1,2,3,4,5]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("응원 선수는 최대 4명까지 선택할 수 있습니다."));

        verify(supportService).addPlayers(ACCOUNT_ID, requestIds);
    }

    @Test
    @DisplayName("[USER-SP-16] 서비스가 PLAYER_NOT_FOUND를 던지면 404와 \"존재하지 않는 선수입니다.\"를 반환한다")
    void addPlayers_unknownPlayer_returns404() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.addPlayers(ACCOUNT_ID, List.of(999L)))
                .willThrow(new BusinessException(ErrorCode.PLAYER_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/support/players")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[999]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 선수입니다."));
    }

    // ---------- 응원 선수 취소 ----------

    @Test
    @DisplayName("[USER-SP-29] PUT으로 선수 취소가 성공하면 200과 남아 있는 응원 선수 목록을 반환한다")
    void opposePlayers_returns200WithRemainingPlayers() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.opposePlayers(ACCOUNT_ID, List.of(3L)))
                .willReturn(List.of(responseOf(2L, "김도영")));

        // when & then
        mockMvc.perform(put("/support/players/oppose")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].playerId").value(2));

        verify(supportService).opposePlayers(ACCOUNT_ID, List.of(3L));
    }

    @Test
    @DisplayName("[USER-SP-29] 응원 선수를 전원 취소하면 200과 빈 배열을 반환한다")
    void opposePlayers_allRemoved_returnsEmptyArray() throws Exception {
        // given
        String token = stubAuthenticatedToken();
        given(supportService.opposePlayers(ACCOUNT_ID, List.of(2L, 3L))).willReturn(List.of());

        // when & then
        mockMvc.perform(put("/support/players/oppose")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // ---------- 인증 (USER-SP-1) ----------

    @Test
    @DisplayName("[USER-SP-1] Authorization 헤더 없이 구단 선택을 호출하면 401이고 서비스는 호출되지 않는다"
            + "(SecurityConfig에 permitAll을 추가하면 이 테스트가 깨진다)")
    void selectTeam_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/support/team")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-1] Authorization 헤더 없이 선수 추가를 호출하면 401이고 서비스는 호출되지 않는다")
    void addPlayers_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/support/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[2]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-1] Authorization 헤더 없이 선수 취소를 호출하면 401이고 서비스는 호출되지 않는다")
    void opposePlayers_withoutToken_returns401() throws Exception {
        mockMvc.perform(put("/support/players/oppose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerIds\":[2]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-1] 만료·위조된 access 토큰으로 호출하면 401이다(구단·선수 목록 조회와 달리 permitAll이 아니다)")
    void selectTeam_withInvalidToken_returns401() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("not-a-jwt")).willReturn(false);

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-1] 탈퇴 계정의 토큰(uid가 활성 계정을 가리키지 않음)으로 호출하면 401이다")
    void selectTeam_withWithdrawnAccountToken_returns401() throws Exception {
        // given: 토큰 자체는 유효하지만 findActiveAuthByUid가 비어 있다(exit_at is null 조건에서 탈락)
        String uid = UUID.randomUUID().toString();
        String token = "access-token-for-" + uid;
        given(jwtTokenProvider.validateToken(token)).willReturn(true);
        given(jwtTokenProvider.isRefreshToken(token)).willReturn(false);
        given(jwtTokenProvider.getUid(token)).willReturn(uid);
        given(userAccountRepository.findActiveAuthByUid(uid)).willReturn(Optional.empty());

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("응원 경로에 매핑되지 않은 메서드(GET)로 헤더 없이 요청해도 405가 아니라 401이다"
            + "(요구사항 ID 없음 — anyRequest().authenticated()가 컨트롤러보다 앞에 있다는 확인)")
    void getOnSupportTeamPath_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/support/team"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
    }

    @Test
    @DisplayName("[USER-SP-1] 인증된 요청은 access 토큰만 허용한다 — refresh 토큰으로는 401이다")
    void selectTeam_withRefreshToken_returns401() throws Exception {
        // given
        given(jwtTokenProvider.validateToken("refresh-token")).willReturn(true);
        given(jwtTokenProvider.isRefreshToken("refresh-token")).willReturn(true);

        // when & then
        mockMvc.perform(post("/support/team")
                        .header("Authorization", "Bearer refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":6}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(supportService);
        // 필터가 refresh 토큰을 인증에 쓰지 않으므로 uid→id 해석 자체가 일어나지 않는다
        verify(userAccountRepository, never()).findActiveAuthByUid(anyString());
    }
}
