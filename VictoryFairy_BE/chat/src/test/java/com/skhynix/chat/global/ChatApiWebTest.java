package com.skhynix.chat.global;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.chat.global.config.SecurityConfig;
import com.skhynix.chat.message.controller.ChatMessageController;
import com.skhynix.chat.message.dto.HistoryResponse;
import com.skhynix.chat.message.dto.SendMessageResponse;
import com.skhynix.chat.message.service.ChatHistoryService;
import com.skhynix.chat.message.service.ChatMessageSendService;
import com.skhynix.chat.message.service.ChatReportService;
import com.skhynix.chat.room.controller.ChatRoomController;
import com.skhynix.chat.room.dto.RoomResponse;
import com.skhynix.chat.room.service.ChatRoomService;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.subscription.controller.ChatSubscriptionController;
import com.skhynix.chat.subscription.service.ChatSubscriptionService;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.ActiveAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.websupport.jwt.JwtProperties;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * 컨트롤러 슬라이스. 인증 필터(실제 {@code JwtAuthenticationFilter})와 공유 예외 핸들러는 진짜를 쓰고 서비스만 목이다.
 *
 * <p>인증 결정: 대부분의 케이스는 {@code authentication()} 후처리기로 Long principal 을 직접 주입한다(필터가
 * 만드는 principal 과 같은 형태 — quiz 의 ChatControllerTestSupport 와 같은 방식). 인증 실패 케이스(CHAT-GC-12)는
 * 실제 JWT 를 {@code Authorization} 헤더에 실어 실제 필터를 통과시킨다.
 */
@WebMvcTest
@ContextConfiguration(classes = {ChatRoomController.class, ChatMessageController.class,
        ChatSubscriptionController.class})
@Import({SecurityConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789",
        "jwt.access-token-validity=3600000",
        "jwt.refresh-token-validity=7200000"
})
class ChatApiWebTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789";
    private static final Long USER = 1L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private ChatRoomService roomService;
    @MockitoBean
    private ChatMessageSendService sendService;
    @MockitoBean
    private ChatHistoryService historyService;
    @MockitoBean
    private ChatReportService reportService;
    @MockitoBean
    private ChatSubscriptionService subscriptionService;
    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }

    private static JwtTokenProvider providerWith(String secret, long accessValidityMs) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAccessTokenValidity(accessValidityMs);
        properties.setRefreshTokenValidity(7_200_000L);
        return new JwtTokenProvider(properties);
    }

    // ---------- 인증 (10, 12) ----------

    private static final String UNAUTHENTICATED_BODY =
            "{\"success\":false,\"data\":null,\"message\":\"인증이 필요합니다.\"}";

    @Test
    @DisplayName("[CHAT-GC-12] Authorization 헤더가 없으면 401 이고 본문은 RestAuthenticationEntryPoint 의 UNAUTHENTICATED 이다")
    void rooms_noToken_is401() throws Exception {
        mockMvc.perform(get("/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));
    }

    @Test
    @DisplayName("[CHAT-GC-12] 서명이 맞지 않는 토큰은 401 이다")
    void rooms_tokenSignedWithOtherSecret_is401() throws Exception {
        String forged = providerWith("another-secret-another-secret-another-secret-9999", 3_600_000L).createAccessToken("uid-1");

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));
    }

    @Test
    @DisplayName("[CHAT-GC-12] 만료된 액세스 토큰은 401 이다")
    void rooms_expiredToken_is401() throws Exception {
        String expired = providerWith(SECRET, -10_000L).createAccessToken("uid-1");

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));
    }

    @Test
    @DisplayName("[CHAT-GC-12] 리프레시 토큰으로는 접근할 수 없다(401)")
    void rooms_refreshToken_is401() throws Exception {
        String refresh = tokenProvider.createRefreshToken("uid-1");
        given(userAccountRepository.findActiveAuthByUid("uid-1")).willReturn(Optional.of(new ActiveAccountView(USER, null)));

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));
    }

    @Test
    @DisplayName("[CHAT-GC-12] 비밀번호 변경 이전에 발급된 토큰은 401 이다")
    void rooms_tokenIssuedBeforePasswordChange_is401() throws Exception {
        String token = tokenProvider.createAccessToken("uid-1");
        long changedInFuture = System.currentTimeMillis() / 1000 + 3600;
        given(userAccountRepository.findActiveAuthByUid("uid-1"))
                .willReturn(Optional.of(new ActiveAccountView(USER, changedInFuture)));

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));
    }

    @Test
    @DisplayName("[CHAT-GC-12] 탈퇴한(활성 계정 조회가 비는) 계정의 토큰은 401 이다")
    void rooms_withdrawnAccount_is401() throws Exception {
        String token = tokenProvider.createAccessToken("uid-1");
        given(userAccountRepository.findActiveAuthByUid("uid-1")).willReturn(Optional.empty());

        mockMvc.perform(get("/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[CHAT-GC-12] 유효한 액세스 토큰이면 실제 JWT 필터를 통과해 principal(Long)이 컨트롤러까지 전달된다")
    void subscribe_validToken_principalReachesService() throws Exception {
        String token = tokenProvider.createAccessToken("uid-1");
        given(userAccountRepository.findActiveAuthByUid("uid-1")).willReturn(Optional.of(new ActiveAccountView(77L, null)));
        given(subscriptionService.subscribe(eq("G1"), eq(77L), any())).willReturn(new SseEmitter());

        mockMvc.perform(get("/rooms/G1/subscribe").header("Authorization", "Bearer " + token)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(subscriptionService).subscribe("G1", 77L, null);
    }

    @Test
    @DisplayName("[CHAT-GC-12] SSE 구독도 인증이 없으면 스트림을 열기 전에 같은 401 이다")
    void subscribe_noToken_is401BeforeOpeningStream() throws Exception {
        mockMvc.perform(get("/rooms/G1/subscribe").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHENTICATED_BODY, true));

        verify(subscriptionService, never()).subscribe(any(), any(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-10] 미인증 DELETE /rooms/x/subscribe 는 401 이다")
    void unsubscribe_noToken_is401() throws Exception {
        mockMvc.perform(delete("/rooms/x/subscribe")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[CHAT-GC-10] 미인증 전송·히스토리·신고·상세도 전부 401 이다")
    void everyRoomEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/rooms/G1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/rooms/G1/messages").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/rooms/G1/messages")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/rooms/G1/messages/1/report")).andExpect(status().isUnauthorized());
    }

    // ---------- 방 목록·상세 (26~28) ----------

    @Test
    @DisplayName("[CHAT-GC-26] 방 목록은 200 이고 항목은 gameId·homeTeam·homeTeamId·awayTeam·awayTeamId·gameDate·gameState 7개 필드다")
    void getRooms_returnsSevenFieldItems() throws Exception {
        given(roomService.getRooms()).willReturn(List.of(new RoomResponse("20261009HTLG0", "LG", 1L, "두산", 2L,
                LocalDateTime.of(2026, 10, 9, 18, 30), "IN_PROGRESS")));

        mockMvc.perform(get("/rooms").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].gameId").value("20261009HTLG0"))
                .andExpect(jsonPath("$.data[0].homeTeam").value("LG"))
                .andExpect(jsonPath("$.data[0].homeTeamId").value(1))
                .andExpect(jsonPath("$.data[0].awayTeam").value("두산"))
                .andExpect(jsonPath("$.data[0].awayTeamId").value(2))
                .andExpect(jsonPath("$.data[0].gameDate").exists())
                .andExpect(jsonPath("$.data[0].gameState").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data[0].length()").value(7));
    }

    @Test
    @DisplayName("[CHAT-GC-21] 경기 없는 날 목록은 200 이고 data 는 빈 배열이다")
    void getRooms_noGames_returnsEmptyArray() throws Exception {
        given(roomService.getRooms()).willReturn(List.of());

        mockMvc.perform(get("/rooms").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("[CHAT-GC-27] 방 상세는 200 이고 목록 항목과 같은 7개 필드다")
    void getRoom_returnsSameFieldsAsListItem() throws Exception {
        given(roomService.getRoom("20261009HTLG0")).willReturn(new RoomResponse("20261009HTLG0", "LG", 1L, "두산", 2L,
                LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED"));

        mockMvc.perform(get("/rooms/20261009HTLG0").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data.gameState").value("SCHEDULED"));
    }

    @Test
    @DisplayName("[CHAT-GC-28] 없는 방 상세는 404 와 '존재하지 않는 채팅방입니다.' 이다")
    void getRoom_notFound_is404() throws Exception {
        given(roomService.getRoom("old")).willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/rooms/old").with(authenticatedAs(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("존재하지 않는 채팅방입니다."));
    }

    @Test
    @DisplayName("[CHAT-GC-29] Redis 장애로 상세가 503 이면 CHAT_BROKER_UNAVAILABLE 문구로 내려간다")
    void getRoom_brokerUnavailable_is503() throws Exception {
        given(roomService.getRoom("G1")).willThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE));

        mockMvc.perform(get("/rooms/G1").with(authenticatedAs(USER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("채팅 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요."));
    }

    // ---------- 전송 (50~53, 58, 105) ----------

    private String body(String content, String clientMsgId) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("content", content, "clientMsgId", clientMsgId));
    }

    @Test
    @DisplayName("[CHAT-GC-50] 전송은 202 이고 본문은 {success:true, data:{gameId, msgId, content}, message:null} 이다")
    void send_returns202WithThreeFieldBody() throws Exception {
        given(sendService.send(eq("G1"), eq(USER), any())).willReturn(new SendMessageResponse("G1", 4402L, "오늘 이긴다"));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오늘 이긴다", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.gameId").value("G1"))
                .andExpect(jsonPath("$.data.msgId").value(4402))
                .andExpect(jsonPath("$.data.content").value("오늘 이긴다"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.message").value(nullValue()));
    }

    @Test
    @DisplayName("[CHAT-GC-51] 컨트롤러는 @Valid 로 먼저 검증하지 않는다 — 빈 content 본문도 서비스까지 전달된다(404 가 400 보다 먼저이기 때문)")
    void send_blankContent_stillReachesService() throws Exception {
        given(sendService.send(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(post("/rooms/nope/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "abc")))
                .andExpect(status().isNotFound());

        verify(sendService).send(eq("nope"), eq(USER), any());
    }

    @Test
    @DisplayName("[CHAT-GC-52] 서비스가 던진 Bean Validation 실패는 400 이고 data.content 에 위반 메시지가 실린다(공유 GlobalExceptionHandler 형식)")
    void send_validationFailure_is400WithFieldMessage() throws Exception {
        org.springframework.validation.BeanPropertyBindingResult result =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "request");
        result.addError(new org.springframework.validation.FieldError("request", "content", "must not be blank"));
        org.springframework.core.MethodParameter parameter = com.skhynix.chat.global.validation.RequestBodyValidator
                .parameterOf(ChatMessageSendService.class, "send", 2, String.class, Long.class,
                        com.skhynix.chat.message.dto.SendMessageRequest.class);
        given(sendService.send(any(), any(), any()))
                .willThrow(new org.springframework.web.bind.MethodArgumentNotValidException(parameter, result));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.content").value("must not be blank"));
    }

    @Test
    @DisplayName("본문 파싱 실패(깨진 JSON)는 컨트롤러 전에 400 으로 끝난다")
    void send_malformedJson_is400() throws Exception {
        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest());

        verify(sendService, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("본문이 없으면 400, Content-Type 이 JSON 이 아니면 415 이다")
    void send_missingBodyAndWrongContentType() throws Exception {
        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER)).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("[CHAT-GC-58] 속도 제한 초과는 429 와 CHAT_RATE_LIMIT_EXCEEDED 문구다")
    void send_rateLimited_is429() throws Exception {
        given(sendService.send(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHAT_RATE_LIMIT_EXCEEDED));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("a", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("[CHAT-GC-105] 처리 중인 clientMsgId 재요청은 409 와 CHAT_MESSAGE_IN_FLIGHT 문구다")
    void send_inFlight_is409() throws Exception {
        given(sendService.send(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHAT_MESSAGE_IN_FLIGHT));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("a", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("같은 메시지를 처리하고 있습니다. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("[CHAT-GC-62] Kafka 장애는 503 이다")
    void send_brokerUnavailable_is503() throws Exception {
        given(sendService.send(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("a", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("[CHAT-GC-57] 마스킹 필터 예외 등 예상 못 한 서비스 예외는 202 가 아니라 500 이다")
    void send_unexpectedFailure_is500() throws Exception {
        given(sendService.send(any(), any(), any())).willThrow(new IllegalStateException("filter down"));

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("a", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isInternalServerError());
    }

    // ---------- 히스토리 (68, 74) ----------

    @Test
    @DisplayName("[CHAT-GC-68] 히스토리는 200 이고 data 키는 messages·nextCursor·hasNext 뿐이다(page·totalElements·totalPages 없음)")
    void history_returnsCursorShape() throws Exception {
        given(historyService.getHistory(eq("G1"), any(), eq(USER))).willReturn(new HistoryResponse(
                List.of(new ChatMessageView(7129L, "내용", "닉", "OB", null, "2026-10-09T19:00:00.000+09:00")), 7129L, true));

        mockMvc.perform(get("/rooms/G1/messages").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.messages[0].msgId").value(7129))
                .andExpect(jsonPath("$.data.messages[0].length()").value(6))
                .andExpect(jsonPath("$.data.nextCursor").value(7129))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.page").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist());
    }

    @Test
    @DisplayName("[CHAT-GC-69] cursor 쿼리 파라미터는 Long 으로 서비스에 전달되고 없으면 null 이다")
    void history_cursorParameterIsPassedAsLong() throws Exception {
        given(historyService.getHistory(any(), any(), any())).willReturn(new HistoryResponse(List.of(), null, false));

        mockMvc.perform(get("/rooms/G1/messages?cursor=7100").with(authenticatedAs(USER))).andExpect(status().isOk());
        mockMvc.perform(get("/rooms/G1/messages").with(authenticatedAs(USER))).andExpect(status().isOk());

        verify(historyService).getHistory("G1", 7100L, USER);
        verify(historyService).getHistory("G1", null, USER);
    }

    @Test
    @DisplayName("[CHAT-GC-74] cursor 가 정수가 아니면 400 '요청 파라미터 형식이 올바르지 않습니다: cursor' 이다")
    void history_nonNumericCursor_is400() throws Exception {
        mockMvc.perform(get("/rooms/G1/messages?cursor=abc").with(authenticatedAs(USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: cursor"));

        verify(historyService, never()).getHistory(any(), any(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-75] 히스토리 Redis 장애는 200 빈 배열이 아니라 503 이다")
    void history_redisDown_is503() throws Exception {
        given(historyService.getHistory(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE));

        mockMvc.perform(get("/rooms/G1/messages").with(authenticatedAs(USER)))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- 신고 (77~85) ----------

    @Test
    @DisplayName("[CHAT-GC-77] 신고 성공은 200 {success:true, data:null, message:null} 이다")
    void report_success_is200WithNullData() throws Exception {
        mockMvc.perform(post("/rooms/G1/messages/4200/report").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()));

        verify(reportService).report("G1", 4200L, USER);
    }

    @Test
    @DisplayName("[CHAT-GC-85] msgId 가 정수가 아니면 400 '요청 파라미터 형식이 올바르지 않습니다: msgId' 이다")
    void report_nonNumericMsgId_is400() throws Exception {
        mockMvc.perform(post("/rooms/G1/messages/abc/report").with(authenticatedAs(USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: msgId"));

        verify(reportService, never()).report(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-80] 본인 메시지 신고는 403 이다")
    void report_ownMessage_is403() throws Exception {
        doThrow(new BusinessException(ErrorCode.SELF_REPORT_NOT_ALLOWED)).when(reportService).report(any(), anyLong(), any());

        mockMvc.perform(post("/rooms/G1/messages/4200/report").with(authenticatedAs(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("자신의 메시지는 신고할 수 없습니다."));
    }

    @Test
    @DisplayName("[CHAT-GC-79] 엔트리가 없으면 404 CHAT_MESSAGE_NOT_FOUND 이다")
    void report_missingMessage_is404() throws Exception {
        doThrow(new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND)).when(reportService).report(any(), anyLong(), any());

        mockMvc.perform(post("/rooms/G1/messages/4200/report").with(authenticatedAs(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 메시지입니다."));
    }

    @Test
    @DisplayName("[CHAT-GC-82] 툼스톤 발행 실패는 503 이다")
    void report_brokerDown_is503() throws Exception {
        doThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE)).when(reportService).report(any(), anyLong(), any());

        mockMvc.perform(post("/rooms/G1/messages/4200/report").with(authenticatedAs(USER)))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- 구독·퇴장 (30, 31, 37, 45) ----------

    @Test
    @DisplayName("[CHAT-GC-30] 구독은 text/event-stream 으로 비동기 시작되고 Last-Event-ID 헤더를 서비스에 그대로 전달한다")
    void subscribe_startsEventStreamAndPassesLastEventId() throws Exception {
        given(subscriptionService.subscribe(eq("G1"), eq(USER), eq("120"))).willReturn(new SseEmitter());

        MvcResult result = mockMvc.perform(get("/rooms/G1/subscribe").with(authenticatedAs(USER))
                        .header("Last-Event-ID", "120")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        verify(subscriptionService).subscribe("G1", USER, "120");
    }

    @Test
    @DisplayName("[CHAT-GC-41] Last-Event-ID 헤더가 없으면 null 이 서비스에 전달된다")
    void subscribe_withoutLastEventId_passesNull() throws Exception {
        given(subscriptionService.subscribe(any(), any(), any())).willReturn(new SseEmitter());

        mockMvc.perform(get("/rooms/G1/subscribe").with(authenticatedAs(USER)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(subscriptionService).subscribe("G1", USER, null);
    }

    @Test
    @DisplayName("[CHAT-GC-31] 방이 없으면 스트림 대신 404 JSON 이다")
    void subscribe_roomMissing_is404Json() throws Exception {
        given(subscriptionService.subscribe(any(), any(), any())).willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/rooms/old/subscribe").with(authenticatedAs(USER)).accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장은 200 {success:true, data:null, message:null} 이다")
    void unsubscribe_returns200WithNullData() throws Exception {
        mockMvc.perform(delete("/rooms/G1/subscribe").with(authenticatedAs(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()));

        verify(subscriptionService).unsubscribe("G1", USER);
    }

    @Test
    @DisplayName("[CHAT-GC-13] 컨트롤러 계층 어디에도 응원 구단 검사가 없다 — 구단 정보 없이 인증만으로 전송·구독·신고가 서비스까지 도달한다")
    void anyAuthenticatedUser_reachesServices() throws Exception {
        given(sendService.send(any(), any(), any())).willReturn(new SendMessageResponse("G1", 1L, "x"));
        given(subscriptionService.subscribe(any(), any(), any())).willReturn(new SseEmitter());

        mockMvc.perform(post("/rooms/G1/messages").with(authenticatedAs(999L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/rooms/G1/subscribe").with(authenticatedAs(999L)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
        mockMvc.perform(post("/rooms/G1/messages/1/report").with(authenticatedAs(999L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[CHAT-GC-1] 컨트롤러 매핑에 /chat 접두사가 없다 — 접두사는 context-path 가 붙인다(/rooms/** 만 존재)")
    void mappings_haveNoContextPathPrefix() throws Exception {
        for (Class<?> controller : List.of(ChatRoomController.class, ChatMessageController.class,
                ChatSubscriptionController.class)) {
            String[] paths = controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value();
            org.assertj.core.api.Assertions.assertThat(paths).containsExactly("/rooms");
        }
    }
}
