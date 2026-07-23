package com.skhynix.quiz.chat.controller;

import static com.skhynix.quiz.chat.controller.ChatControllerTestSupport.authenticatedAs;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.service.ChatService;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/chat/rooms/{roomUid}/messages/{messageId}/report}(신고→즉시 blind) 슬라이스 테스트.
 *
 * <p>blind 상태 전이 자체({@code Chat.blind()})와 멱등·자기신고·삭제메시지 판정 로직은
 * {@code ChatService}가 목이라 이 슬라이스에서 검증되지 않는다(그건 {@code ChatServiceTest} 소관).
 * 여기서는 서비스가 반환하는 결과(정상/예외)에 따라 컨트롤러가 올바른 상태 코드로 응답하는지만 본다.
 */
@WebMvcTest(ChatController.class)
@ContextConfiguration(classes = ChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatControllerReportTest {

    private static final Long USER_ID = 1L;
    private static final String ROOM_UID = "room-uid-1";
    private static final Long MESSAGE_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("[AC-CHAT-20-1] 타인의 정상 메시지를 신고하면 200을 반환한다(대상은 즉시 blind, 서비스 책임)")
    void reportMessage_normalMessage_returns200() throws Exception {
        willDoNothing().given(chatService).reportMessage(eq(ROOM_UID), eq(MESSAGE_ID), eq(USER_ID));

        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("[AC-CHAT-20-2] 존재하지 않는 messageId를 신고하면 404를 반환한다")
    void reportMessage_messageNotFound_returns404() throws Exception {
        willThrow(new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND))
                .given(chatService).reportMessage(eq(ROOM_UID), eq(999L), eq(USER_ID));

        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, 999L)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.CHAT_MESSAGE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-20-3] 미인증 요청으로 신고하면 401을 반환한다")
    void reportMessage_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[AC-CHAT-27-1] 자기 메시지를 신고하면 403을 반환한다")
    void reportMessage_selfReport_returns403() throws Exception {
        willThrow(new BusinessException(ErrorCode.SELF_REPORT_NOT_ALLOWED))
                .given(chatService).reportMessage(eq(ROOM_UID), eq(MESSAGE_ID), eq(USER_ID));

        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(ErrorCode.SELF_REPORT_NOT_ALLOWED.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-28-1] 이미 blind인 메시지를 재신고해도 멱등하게 200을 반환한다")
    void reportMessage_alreadyBlindMessage_isIdempotentAndReturns200Twice() throws Exception {
        willDoNothing().given(chatService).reportMessage(eq(ROOM_UID), eq(MESSAGE_ID), eq(USER_ID));

        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        // 재신고
        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[AC-CHAT-29-1] 이미 소프트삭제된 메시지를 신고하면 404를 반환한다")
    void reportMessage_deletedMessage_returns404() throws Exception {
        willThrow(new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND))
                .given(chatService).reportMessage(eq(ROOM_UID), eq(MESSAGE_ID), eq(USER_ID));

        mockMvc.perform(post("/api/chat/rooms/{roomUid}/messages/{messageId}/report", ROOM_UID, MESSAGE_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }
}
