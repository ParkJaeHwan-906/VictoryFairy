package com.skhynix.quiz.chat.controller;

import static com.skhynix.quiz.chat.controller.ChatControllerTestSupport.authenticatedAs;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.SendMessageRequest;
import com.skhynix.quiz.chat.service.ChatService;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /chat/rooms/{roomUid}/messages}(메시지 전송) 슬라이스 테스트.
 *
 * <p>content 검증({@code @NotBlank}·{@code @Size(max=500)})은 {@code @Valid}가 컨트롤러 진입 전(인자
 * 바인딩 단계)에 수행하므로, 위반 케이스는 {@code ChatService}를 전혀 호출하지 않는다는 것까지 함께
 * 고정한다. {@link #sendMessage_nonexistentRoomAndInvalidContent_400TakesPriorityOver404()}가
 * AC-CHAT-14-3 검증 우선순위 계약(400이 404보다 우선)을 못 박는다.
 */
@WebMvcTest(ChatController.class)
@ContextConfiguration(classes = ChatController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChatControllerSendMessageTest {

    private static final Long USER_ID = 1L;
    private static final String ROOM_UID = "room-uid-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private String requestJson(String content) throws Exception {
        return objectMapper.writeValueAsString(new SendMessageRequest(content));
    }

    @Test
    @DisplayName("[AC-CHAT-10-1] 유효한 content로 전송하면 201과 저장된 메시지를 반환하고 서비스에 위임한다")
    void sendMessage_validContent_returns201AndDelegatesToService() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 20, 10, 0);
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("안녕")))
                .willReturn(new MessageResponse(1L, "안녕", "두산팬1", "user-profile-img/send.jpg", createdAt));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").value("안녕"))
                .andExpect(jsonPath("$.data.senderNickname").value("두산팬1"))
                .andExpect(jsonPath("$.data.profileImgUrl").value("user-profile-img/send.jpg"))
                .andExpect(jsonPath("$.data.createdAt").exists());

        verify(chatService).sendMessage(ROOM_UID, USER_ID, "안녕");
    }

    @Test
    @DisplayName("[AC-CHAT-10-1 확장] 프로필 이미지가 없는 발신자의 전송 응답은 profileImgUrl이 null로 실린다"
            + "(빈 문자열도 기본 이미지 URL도 아님)")
    void sendMessage_senderWithoutProfileImg_responseProfileImgUrlIsNull() throws Exception {
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("안녕")))
                .willReturn(new MessageResponse(1L, "안녕", "두산팬1", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.profileImgUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("[QUIZ-CPF-3/6] 금지어만 담긴 content 도 거절되지 않는다 — 201 이고 본문 data.content 는 "
            + "서비스가 돌려준 마스킹 결과 그대로다(발신자는 SSE 에코를 안 받으므로 이 응답이 치환을 아는 유일한 지점)")
    void sendMessage_profanityOnlyContent_returns201WithMaskedContent() throws Exception {
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("시발")))
                .willReturn(new MessageResponse(1L, "망곰", "두산팬1", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("시발")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("망곰"));

        // 컨트롤러는 원문을 그대로 서비스에 넘긴다 — 마스킹은 서비스가 검증 4단계 뒤에 한다(QUIZ-CPF-7).
        verify(chatService).sendMessage(ROOM_UID, USER_ID, "시발");
    }

    @Test
    @DisplayName("[QUIZ-CPF-5] 마스킹 발생 여부를 알리는 필드가 응답에 추가되지 않는다 — "
            + "data 의 키 집합이 종전 5개(id·content·senderNickname·profileImgUrl·createdAt) 그대로다")
    void sendMessage_responseKeySetUnchangedByMasking() throws Exception {
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("시발")))
                .willReturn(new MessageResponse(1L, "망곰", "두산팬1", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("시발")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.content").exists())
                .andExpect(jsonPath("$.data.senderNickname").exists())
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.masked").doesNotExist())
                .andExpect(jsonPath("$.data.filtered").doesNotExist())
                .andExpect(jsonPath("$.data.originalContent").doesNotExist());
    }

    @Test
    @DisplayName("[AC-CHAT-4-1] 인증 헤더 없이 메시지를 전송하면 401을 반환하고 서비스는 호출되지 않는다")
    void sendMessage_withoutAuthentication_returns401AndNeverCallsService() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isUnauthorized());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-12-1] content가 null이면 400을 반환하고 저장하지 않는다")
    void sendMessage_nullContent_returns400WithoutSaving() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(null)))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-12-2] content가 빈 문자열이면 400을 반환하고 저장하지 않는다")
    void sendMessage_emptyContent_returns400WithoutSaving() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-12-3] content가 공백 3칸이면 400을 반환하고 저장하지 않는다")
    void sendMessage_blankContent_returns400WithoutSaving() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("   ")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-12-4] content가 개행·탭만이면 400을 반환하고 저장하지 않는다(trim 후 빈 값)")
    void sendMessage_whitespaceOnlyContent_returns400WithoutSaving() throws Exception {
        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("\n\t")))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-12-5] 공백이 아닌 1자 content는 201로 통과한다(최소 유효 경계)")
    void sendMessage_singleCharContent_returns201() throws Exception {
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("a")))
                .willReturn(new MessageResponse(1L, "a", "닉네임", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("a")))
                .andExpect(status().isCreated());

        verify(chatService).sendMessage(ROOM_UID, USER_ID, "a");
    }

    @Test
    @DisplayName("[AC-CHAT-13-1] content가 정확히 500자면 201로 통과한다(상한 포함 경계)")
    void sendMessage_exactly500Chars_returns201() throws Exception {
        String content = "a".repeat(500);
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq(content)))
                .willReturn(new MessageResponse(1L, content, "닉네임", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(content)))
                .andExpect(status().isCreated());

        verify(chatService).sendMessage(ROOM_UID, USER_ID, content);
    }

    @Test
    @DisplayName("[AC-CHAT-13-2] content가 501자면 400을 반환하고 저장하지 않는다")
    void sendMessage_501Chars_returns400WithoutSaving() throws Exception {
        String content = "a".repeat(501);

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(content)))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-13-3] 이모지(surrogate pair) 250개는 String.length()가 500이라 201로 통과한다")
    void sendMessage_250SurrogatePairEmojis_returns201() throws Exception {
        String content = "😀".repeat(250); // 😀 x250, length() == 500
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq(content)))
                .willReturn(new MessageResponse(1L, content, "닉네임", null, LocalDateTime.now()));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(content)))
                .andExpect(status().isCreated());

        verify(chatService).sendMessage(ROOM_UID, USER_ID, content);
    }

    @Test
    @DisplayName("[AC-CHAT-13-3] 이모지 251개는 String.length()가 502라 400을 반환하고 저장하지 않는다")
    void sendMessage_251SurrogatePairEmojis_returns400() throws Exception {
        String content = "😀".repeat(251); // length() == 502

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(content)))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-CHAT-14-1] 존재하지 않는 방으로 전송하면 404를 반환하고 저장하지 않는다")
    void sendMessage_roomNotFound_returns404() throws Exception {
        given(chatService.sendMessage(eq("nope"), eq(USER_ID), eq("안녕")))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", "nope")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ErrorCode.CHATROOM_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-14-2] 소프트삭제된 방으로 전송하면 404를 반환하고 저장하지 않는다")
    void sendMessage_softDeletedRoom_returns404() throws Exception {
        given(chatService.sendMessage(eq("deleted-uid"), eq(USER_ID), eq("안녕")))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", "deleted-uid")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-11] 내 응원 구단 방이 아니면 전송은 403 CHATROOM_TEAM_MISMATCH를 반환하고 저장하지 않는다")
    void sendMessage_teamMismatch_returns403WithoutSaving() throws Exception {
        given(chatService.sendMessage(eq("other-team-uid"), eq(USER_ID), eq("안녕")))
                .willThrow(new BusinessException(ErrorCode.CHATROOM_TEAM_MISMATCH));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", "other-team-uid")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value(ErrorCode.CHATROOM_TEAM_MISMATCH.getMessage()));
    }

    @Test
    @DisplayName("[QUIZ-CTAC-15] 응원 구단이 없으면 전송은 400 SUPPORT_TEAM_REQUIRED를 반환하고 저장하지 않는다")
    void sendMessage_noSupportTeam_returns400WithoutSaving() throws Exception {
        given(chatService.sendMessage(eq(ROOM_UID), eq(USER_ID), eq("안녕")))
                .willThrow(new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", ROOM_UID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("안녕")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.SUPPORT_TEAM_REQUIRED.getMessage()));
    }

    @Test
    @DisplayName("[AC-CHAT-14-3] 방 미존재 + content 501자 위반이 동시에 발생하면 400이 우선한다"
            + "(@Valid가 바인딩 단계에서 먼저 판정돼 서비스는 호출조차 되지 않는다)")
    void sendMessage_nonexistentRoomAndInvalidContent_400TakesPriorityOver404() throws Exception {
        String tooLong = "a".repeat(501);

        mockMvc.perform(post("/chat/rooms/{roomUid}/messages", "definitely-does-not-exist")
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(tooLong)))
                .andExpect(status().isBadRequest());

        verify(chatService, never()).sendMessage(anyString(), anyLong(), anyString());
    }
}
