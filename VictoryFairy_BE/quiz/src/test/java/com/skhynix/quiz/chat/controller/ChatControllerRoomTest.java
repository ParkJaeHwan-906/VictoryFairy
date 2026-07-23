package com.skhynix.quiz.chat.controller;

import static com.skhynix.quiz.chat.controller.ChatControllerTestSupport.authenticatedAs;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.chat.service.ChatService;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code ChatController}의 방 목록·상세·구독(SSE) 슬라이스 테스트.
 *
 * <p>{@code ChatService}는 {@code @MockitoBean}으로 대체해 컨트롤러의 상태 코드·JSON 매핑·인가 배선만
 * 검증한다. 소프트삭제 방 제외(findAllByDeletedAtIsNull) 같은 실제 필터링 로직은 리포지토리 쿼리
 * 책임이라 이 슬라이스에서는 서비스가 돌려준 값을 그대로 통과시키는지만 확인한다(진짜 필터링 검증은
 * DB 없이는 불가 — 미커버 영역으로 별도 보고).
 *
 * <p>인증 주입은 {@link ChatControllerTestSupport#authenticatedAs(Long)}를 사용한다(이유는 그 클래스
 * Javadoc 참고).
 */
@WebMvcTest(ChatController.class)
@ContextConfiguration(classes = ChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatControllerRoomTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    @Test
    @DisplayName("[AC-CHAT-2-1] 인증 사용자가 방 목록을 요청하면 200과 서비스가 반환한 방 목록을 그대로 반환한다")
    void getRooms_authenticated_returns200WithRoomList() throws Exception {
        List<RoomResponse> rooms = List.of(
                new RoomResponse("uid-1", "두산", "두산 채팅방", 3),
                new RoomResponse("uid-2", "롯데", "롯데 채팅방", 0));
        given(chatService.getRooms()).willReturn(rooms);

        mockMvc.perform(get("/api/chat/rooms").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].roomUid").value("uid-1"))
                .andExpect(jsonPath("$.data[0].team").value("두산"))
                .andExpect(jsonPath("$.data[0].participants").value(3))
                .andExpect(jsonPath("$.data[1].roomUid").value("uid-2"));
    }

    @Test
    @DisplayName("[AC-CHAT-2-2] 삭제 안 된 방이 0개면 200과 빈 배열을 반환한다(404 아님)")
    void getRooms_noRooms_returns200WithEmptyArray() throws Exception {
        given(chatService.getRooms()).willReturn(List.of());

        mockMvc.perform(get("/api/chat/rooms").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("[AC-CHAT-3-1] 존재하지 않는 roomUid로 방 상세를 조회하면 404를 반환한다")
    void getRoom_notFound_returns404() throws Exception {
        given(chatService.getRoom(eq("nope"))).willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/api/chat/rooms/{roomUid}", "nope").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.CHATROOM_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-3-2] 소프트삭제된 방의 roomUid로 조회해도 없는 방과 동일하게 404를 반환한다")
    void getRoom_softDeleted_returns404SameAsNotFound() throws Exception {
        given(chatService.getRoom(eq("deleted-uid")))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/api/chat/rooms/{roomUid}", "deleted-uid").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[AC-CHAT-4-1] 인증 헤더 없이 방 목록을 요청하면 401을 반환한다")
    void getRooms_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-6-2] Authorization 헤더 없이 SSE 구독을 요청하면 401을 반환한다"
            + "(표준 브라우저 EventSource는 헤더를 못 실어 이 경로로 귀결됨을 서버 관점에서 고정)")
    void subscribe_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/chat/rooms/{roomUid}/subscribe", "uid-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[AC-CHAT-6-1] 인증 사용자가 존재하는 방을 구독하면 200과 text/event-stream으로 연결이 열린다")
    void subscribe_authenticated_opens200EventStream() throws Exception {
        SseEmitter emitter = new SseEmitter();
        given(chatService.subscribe(eq("uid-1"), eq(USER_ID))).willReturn(emitter);

        MvcResult result = mockMvc.perform(get("/api/chat/rooms/{roomUid}/subscribe", "uid-1")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 실제 스트리밍 이벤트를 재현하지 않고, 응답 상태·Content-Type만 확인하기 위해 emitter를 즉시 완료시킨다.
        emitter.complete();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("[AC-CHAT-9-1] 소프트삭제된 방을 구독 요청하면 스트림을 열지 않고 404를 반환한다")
    void subscribe_softDeletedRoom_returns404WithoutOpeningStream() throws Exception {
        given(chatService.subscribe(eq("deleted-uid"), eq(USER_ID)))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(get("/api/chat/rooms/{roomUid}/subscribe", "deleted-uid")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound());
    }
}
