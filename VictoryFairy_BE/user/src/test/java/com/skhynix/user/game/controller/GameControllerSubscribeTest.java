package com.skhynix.user.game.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.game.service.GameService;
import com.skhynix.user.game.service.GameSubscriptionService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.time.LocalDate;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /games/subscribe}·{@code GET /games/support/subscribe}(외부 경로 {@code /api/games/...})의
 * 인증 배선과 파라미터 전달을 검증한다. 슬라이스 구성은 {@link GameControllerTest}와 같다 — 실제
 * {@link SecurityConfig}를 태우고 {@link GameSubscriptionService}는 목이다(snapshot·팬아웃은 서비스·레지스트리
 * 테스트 몫). {@code SseEmitter}를 반환하는 핸들러는 비동기 응답이라 {@code asyncStarted}로 판정한다.
 *
 * <p><b>MockMvc는 context-path를 적용하지 않는다</b> — 이 슬라이스의 경로는 {@code /games/subscribe}다.
 */
@WebMvcTest(GameController.class)
@ContextConfiguration(classes = GameController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class GameControllerSubscribeTest {

    private static final String UNAUTHENTICATED_MESSAGE = "인증이 필요합니다.";
    private static final Long ACCOUNT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private GameSubscriptionService gameSubscriptionService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

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

    @Test
    @DisplayName("GET /games/subscribe 는 인증 없이 200 + text/event-stream 으로 열리고 date 가 서비스에 그대로 간다")
    void subscribe_withoutAuth_opensEventStream() throws Exception {
        given(gameSubscriptionService.subscribe(LocalDate.of(2026, 8, 1))).willReturn(new SseEmitter());

        mockMvc.perform(get("/games/subscribe").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        verify(gameSubscriptionService).subscribe(LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("date 를 생략하면 서비스에 null 이 전달된다 — 오늘 판정은 서비스 몫(GET /games 와 같은 규칙)")
    void subscribe_missingDate_passesNull() throws Exception {
        given(gameSubscriptionService.subscribe(isNull())).willReturn(new SseEmitter());

        mockMvc.perform(get("/games/subscribe"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(gameSubscriptionService).subscribe(isNull());
    }

    @Test
    @DisplayName("date 형식이 어긋나면 400 ApiResponse 래퍼 — GET /games 와 같은 처리")
    void subscribe_malformedDate_returns400() throws Exception {
        mockMvc.perform(get("/games/subscribe").queryParam("date", "20260801"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(gameSubscriptionService);
    }

    @Test
    @DisplayName("GET /games/support/subscribe 는 인증 없이 401 — /games/support 와 같은 정책")
    void subscribeSupport_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/games/support/subscribe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameSubscriptionService);
    }

    @Test
    @DisplayName("GET /games/support/subscribe 는 유효한 토큰이면 principal 의 계정 id 와 date 로 구독한다")
    void subscribeSupport_withAuth_opensEventStream() throws Exception {
        String token = stubAuthenticatedToken();
        given(gameSubscriptionService.subscribeSupportTeam(ACCOUNT_ID, LocalDate.of(2026, 8, 1)))
                .willReturn(new SseEmitter());

        mockMvc.perform(get("/games/support/subscribe")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        verify(gameSubscriptionService).subscribeSupportTeam(ACCOUNT_ID, LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("POST /games/subscribe 는 permitAll 이 GET 한정이라 401 이다(405 아님)")
    void subscribe_nonGet_returns401() throws Exception {
        mockMvc.perform(post("/games/subscribe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(gameSubscriptionService);
    }

    @Test
    @DisplayName("기존 GET /games 는 그대로 무인증 200 이다 — 구독 경로 추가가 공개 조회를 바꾸지 않는다")
    void existingGetGames_stillPermitAll() throws Exception {
        given(gameService.getGames(any())).willReturn(java.util.List.of());

        mockMvc.perform(get("/games").queryParam("date", "2026-08-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
