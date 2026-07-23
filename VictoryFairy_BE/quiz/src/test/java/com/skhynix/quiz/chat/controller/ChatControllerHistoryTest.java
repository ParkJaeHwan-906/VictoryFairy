package com.skhynix.quiz.chat.controller;

import static com.skhynix.quiz.chat.controller.ChatControllerTestSupport.authenticatedAs;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.service.ChatService;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * {@code GET /api/chat/rooms/{roomUid}/messages}(히스토리 조회) 슬라이스 테스트.
 *
 * <p>{@code ChatService}를 목으로 대체하므로, 최신순 정렬·페이지 크기 30·blind/삭제 제외 같은 실제
 * 필터·정렬 로직(JPQL {@code ORDER BY}·{@code WHERE})은 이 슬라이스가 검증하지 못한다 — 컨트롤러가
 * 서비스 반환값을 있는 그대로 노출하는지만 확인한다. 정렬·필터의 위임 여부(리포지토리 호출 인자)는
 * {@code ChatServiceTest}가, 실제 DB 레벨 필터링 동작은 DB 전략 미비로 커버되지 않는다(보고 대상).
 */
@WebMvcTest(ChatController.class)
@ContextConfiguration(classes = ChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatControllerHistoryTest {

    private static final Long USER_ID = 1L;
    private static final String ROOM_UID = "room-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("[AC-CHAT-18-1] 히스토리 첫 페이지를 요청하면 200과 서비스가 만든 페이지(최신순)를 그대로 반환한다")
    void getHistory_firstPage_returns200WithServicePage() throws Exception {
        List<MessageResponse> messages = List.of(
                new MessageResponse("최신", "닉1", LocalDateTime.of(2026, 7, 20, 12, 0)),
                new MessageResponse("이전", "닉2", LocalDateTime.of(2026, 7, 20, 11, 0)));
        PageResponse<MessageResponse> page = new PageResponse<>(messages, 0, 30, 50L, 2, true);
        given(chatService.getHistory(eq(ROOM_UID), eq(0))).willReturn(page);

        mockMvc.perform(get("/api/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].content").value("최신"))
                .andExpect(jsonPath("$.data.size").value(30))
                .andExpect(jsonPath("$.data.totalElements").value(50))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        verify(chatService).getHistory(ROOM_UID, 0);
    }

    @Test
    @DisplayName("[AC-CHAT-18-2] 메시지가 0개인 방은 200과 빈 목록을 반환한다")
    void getHistory_noMessages_returns200WithEmptyContent() throws Exception {
        PageResponse<MessageResponse> emptyPage = new PageResponse<>(List.of(), 0, 30, 0L, 0, false);
        given(chatService.getHistory(eq(ROOM_UID), eq(0))).willReturn(emptyPage);

        mockMvc.perform(get("/api/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }

    @Test
    @DisplayName("[AC-CHAT-18-3] 메시지가 정확히 30개면 30개를 반환하고 다음 페이지가 없다고 표시한다")
    void getHistory_exactly30Messages_returnsAllWithNoNextPage() throws Exception {
        List<MessageResponse> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(new MessageResponse("메시지" + i, "닉", LocalDateTime.now()));
        }
        PageResponse<MessageResponse> page = new PageResponse<>(messages, 0, 30, 30L, 1, false);
        given(chatService.getHistory(eq(ROOM_UID), eq(0))).willReturn(page);

        mockMvc.perform(get("/api/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(30))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("[AC-CHAT-18-4] 없는 방의 히스토리를 조회하면 404를 반환한다")
    void getHistory_roomNotFound_returns404() throws Exception {
        given(chatService.getHistory(eq("nope"), eq(0)))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/api/chat/rooms/{roomUid}/messages", "nope")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[QUIZ-CHAT-4] 인증 헤더 없이 히스토리를 조회하면 401을 반환한다")
    void getHistory_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/{roomUid}/messages", ROOM_UID))
                .andExpect(status().isUnauthorized());
    }
}
