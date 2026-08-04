package com.skhynix.quiz.chat.controller;

import static com.skhynix.quiz.chat.controller.ChatControllerTestSupport.authenticatedAs;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.service.ChatService;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code DELETE /chat/rooms/{roomUid}/subscribe}(명시적 퇴장) 슬라이스 테스트(F절, QUIZ-CTAC-19~23).
 *
 * <p>{@code ChatService}가 목이라 실제 {@code SseEmitterRegistry} 종료·전파 동작은 여기서 검증되지
 * 않는다({@code ChatServiceTest}·{@code SseEmitterRegistryTest} 소관) — 이 슬라이스는 HTTP 계약
 * (상태 코드·멱등성·구단 가드 미적용)만 본다.
 */
@WebMvcTest(ChatController.class)
@ContextConfiguration(classes = ChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatControllerUnsubscribeTest {

    private static final Long USER_ID = 1L;
    private static final String ROOM_UID = "room-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("[QUIZ-CTAC-19] 인증 사용자가 구독 해제를 요청하면 200과 ApiResponse.ok(null)을 반환하고 서비스에 위임한다")
    void unsubscribe_authenticated_returns200AndDelegatesToService() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq(ROOM_UID), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));

        verify(chatService).unsubscribe(ROOM_UID, USER_ID);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-20] 끊을 구독이 없어도 200을 반환한다(404 아님)")
    void unsubscribe_noExistingSubscription_returns200NotNotFound() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq(ROOM_UID), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-20] 같은 해제 요청을 연속 2회 보내면 둘 다 200이다")
    void unsubscribe_calledTwiceConsecutively_bothReturn200() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq(ROOM_UID), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(chatService, times(2)).unsubscribe(ROOM_UID, USER_ID);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-21] 구단을 바꾼 사용자가 이전 구단 방에 대해 해제를 요청해도 403이 아니라 200이다"
            + "(컨트롤러는 구단 일치 검사를 걸지 않고 그대로 서비스에 위임 — 서비스가 가드를 걸지 않는다는 사실은 "
            + "ChatServiceTest에서 검증)")
    void unsubscribe_forPreviousTeamRoom_returns200NotForbidden() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq("previous-team-room-uid"), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", "previous-team-room-uid")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-22] 응원 구단이 없는 사용자도 해제 요청은 400이 아니라 200이다")
    void unsubscribe_userWithoutSupportTeam_returns200NotBadRequest() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq(ROOM_UID), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID).with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-23] 존재하지 않거나 삭제된 roomUid로 해제를 요청해도 404가 아니라 200이다")
    void unsubscribe_nonexistentRoomUid_returns200NotNotFound() throws Exception {
        willDoNothing().given(chatService).unsubscribe(eq("does-not-exist"), eq(USER_ID));

        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", "does-not-exist")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("인증 헤더 없이 구독 해제를 요청하면 401을 반환하고 서비스는 호출되지 않는다")
    void unsubscribe_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(delete("/chat/rooms/{roomUid}/subscribe", ROOM_UID))
                .andExpect(status().isUnauthorized());

        verify(chatService, never()).unsubscribe(anyString(), anyLong());
    }
}
