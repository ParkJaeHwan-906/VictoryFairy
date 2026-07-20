package com.skhynix.quiz.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageEvent;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ChatService}를 협력 객체(리포지토리·publisher·emitter 레지스트리) 전부를 Mockito로 대체해
 * 단위로 검증한다. DB·Spring 컨텍스트 없음.
 *
 * <p>리포지토리 쿼리 자체(예: {@code findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc}의
 * 실제 WHERE/ORDER BY 동작)는 여기서 목으로 대체되므로 검증되지 않는다 — "서비스가 리포지토리에 올바른
 * 인자로 위임하고, 리포지토리가 돌려준 결과를 가공 없이 그대로 반환한다"는 계약만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final String ROOM_UID = "room-uid-1";

    @Mock
    private ChatroomRepository chatroomRepository;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RealtimeEventPublisher eventPublisher;

    @Mock
    private SseEmitterRegistry emitterRegistry;

    @InjectMocks
    private ChatService chatService;

    private Team team() {
        return Team.builder().name("두산").build();
    }

    private Chatroom activeRoom(String uid) {
        UserAccount owner = userAccountWithId(999L, "시스템계정");
        Chatroom room = Chatroom.builder().team(team()).owner(owner).name("두산 채팅방").build();
        ReflectionTestUtils.setField(room, "uid", uid);
        return room;
    }

    private UserAccount userAccountWithId(Long id, String nickname) {
        UserAccount account = UserAccount.builder().nickname(nickname).password("password1!").build();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private Chat chatOf(Chatroom room, UserAccount author, String content) {
        return Chat.builder().chatroom(room).userAccount(author).content(content).build();
    }

    // ---------- getRooms / getRoom ----------

    @Test
    @DisplayName("getRooms()는 삭제 안 된 방 목록을 조회해 각 방의 participants를 emitterRegistry.count()로 채운다")
    void getRooms_mapsEachRoomWithEmitterRegistryCount() {
        Chatroom room1 = activeRoom("uid-1");
        Chatroom room2 = activeRoom("uid-2");
        given(chatroomRepository.findAllByDeletedAtIsNull()).willReturn(List.of(room1, room2));
        given(emitterRegistry.count("uid-1")).willReturn(3);
        given(emitterRegistry.count("uid-2")).willReturn(0);

        List<RoomResponse> result = chatService.getRooms();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).roomUid()).isEqualTo("uid-1");
        assertThat(result.get(0).participants()).isEqualTo(3);
        assertThat(result.get(1).roomUid()).isEqualTo("uid-2");
        assertThat(result.get(1).participants()).isEqualTo(0);
    }

    @Test
    @DisplayName("getRoom()은 존재하는 방이면 RoomResponse를 반환한다")
    void getRoom_activeRoom_returnsRoomResponse() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(emitterRegistry.count(ROOM_UID)).willReturn(5);

        RoomResponse result = chatService.getRoom(ROOM_UID);

        assertThat(result.roomUid()).isEqualTo(ROOM_UID);
        assertThat(result.participants()).isEqualTo(5);
    }

    @Test
    @DisplayName("getRoom()은 없거나 삭제된 방이면 BusinessException(CHATROOM_NOT_FOUND)을 던진다")
    void getRoom_missingOrDeletedRoom_throwsChatroomNotFound() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getRoom("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
    }

    // ---------- subscribe ----------

    @Test
    @DisplayName("subscribe()는 방이 존재하면 emitterRegistry.register()에 위임해 SseEmitter를 반환한다")
    void subscribe_activeRoom_delegatesToEmitterRegistry() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        SseEmitter emitter = new SseEmitter();
        given(emitterRegistry.register(ROOM_UID, 1L)).willReturn(emitter);

        SseEmitter result = chatService.subscribe(ROOM_UID, 1L);

        assertThat(result).isSameAs(emitter);
        verify(emitterRegistry).register(ROOM_UID, 1L);
    }

    @Test
    @DisplayName("subscribe()는 없거나 삭제된 방이면 404를 던지고 emitterRegistry.register()를 호출하지 않는다")
    void subscribe_missingRoom_throwsAndNeverRegisters() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.subscribe("nope", 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(emitterRegistry, never()).register(anyString(), any());
    }

    // ---------- sendMessage ----------

    @Test
    @DisplayName("[AC-CHAT-10-1] sendMessage()는 방·발신자가 유효하면 blind=false로 저장하고 저장된 메시지를 반환한다")
    void sendMessage_validRoomAndSender_savesWithBlindFalse() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "안녕");

        ArgumentCaptor<Chat> captor = ArgumentCaptor.forClass(Chat.class);
        verify(chatRepository).saveAndFlush(captor.capture());
        Chat saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("안녕");
        assertThat(saved.isBlind()).isFalse();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getUserAccount()).isSameAs(sender);
        assertThat(response.content()).isEqualTo("안녕");
        assertThat(response.senderNickname()).isEqualTo("두산팬1");
    }

    @Test
    @DisplayName("[AC-CHAT-11/15-1] sendMessage()는 저장 후 발신자를 제외 대상으로 지정하고 "
            + "{content, senderNickname, createdAt, roomUid} 4필드(메시지 식별자 없음)로 구성된 "
            + "MessageEvent를 payload로 publish한다")
    void sendMessage_publishesRealtimeEventExcludingSenderWithMessageEventPayload() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        // saveAndFlush 목이 실제 JPA persist처럼 @CreationTimestamp(createdAt)를 채워주지 않으므로,
        // MessageEvent.createdAt() 검증을 의미 있게 만들기 위해 여기서 직접 채워 넣는다.
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> {
            Chat chat = invocation.getArgument(0);
            ReflectionTestUtils.setField(chat, "createdAt", LocalDateTime.of(2026, 7, 20, 9, 0));
            return chat;
        });

        chatService.sendMessage(ROOM_UID, 1L, "안녕");

        ArgumentCaptor<RealtimeEvent> eventCaptor = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq(ROOM_UID), eventCaptor.capture());
        RealtimeEvent event = eventCaptor.getValue();
        assertThat(event.name()).isEqualTo("message");
        assertThat(event.excludeUserAccountId()).isEqualTo(1L);
        assertThat(event.data()).isInstanceOf(MessageEvent.class);
        MessageEvent payload = (MessageEvent) event.data();
        assertThat(payload.content()).isEqualTo("안녕");
        assertThat(payload.senderNickname()).isEqualTo("두산팬1");
        assertThat(payload.roomUid()).isEqualTo(ROOM_UID);
        assertThat(payload.createdAt()).isNotNull();
        // MessageEvent record 자체에 식별자 필드가 없어(전제 6·QUIZ-CHAT-15) 컴파일 타임에 id 노출이 막힌다.
    }

    @Test
    @DisplayName("[AC-CHAT-14-1] sendMessage()는 존재하지 않는 방이면 404를 던지고 저장·발행 모두 하지 않는다")
    void sendMessage_roomNotFound_throwsWithoutSavingOrPublishing() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage("nope", 1L, "안녕"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(chatRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("[AC-CHAT-17] pub/sub 발행이 실패해도 저장은 유지되고 sendMessage()는 예외 없이 응답을 반환한다(fire-and-forget)")
    void sendMessage_publishFails_stillReturnsResponseWithoutPropagatingException() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount sender = userAccountWithId(1L, "두산팬1");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.of(sender));
        given(chatRepository.saveAndFlush(any(Chat.class))).willAnswer(invocation -> invocation.getArgument(0));
        doThrowOnPublish();

        // sendMessage()가 예외를 전파했다면 아래 호출 자체가 실패해 테스트가 깨진다(그것으로 이미 증명됨).
        MessageResponse response = chatService.sendMessage(ROOM_UID, 1L, "안녕");

        assertThat(response.content()).isEqualTo("안녕");
        verify(chatRepository).saveAndFlush(any(Chat.class));
    }

    private void doThrowOnPublish() {
        org.mockito.Mockito.doThrow(new RuntimeException("pub/sub down"))
                .when(eventPublisher).publish(anyString(), any());
    }

    @Test
    @DisplayName("경계(요구사항 미기재, 방어 코드): 발신자 계정을 찾을 수 없으면 UNAUTHENTICATED를 던지고 저장하지 않는다")
    void sendMessage_senderNotFound_throwsUnauthenticated() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(userAccountRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.sendMessage(ROOM_UID, 1L, "안녕"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        verify(chatRepository, never()).saveAndFlush(any());
    }

    // ---------- getHistory ----------

    @Test
    @DisplayName("[AC-CHAT-18] getHistory()는 page·30건 Pageable로 리포지토리에 위임하고 결과를 PageResponse로 감싼다")
    void getHistory_delegatesToRepositoryWithPageableAndWrapsResult() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        UserAccount author = userAccountWithId(1L, "닉네임");
        Chat chat = chatOf(room, author, "내용");
        Page<Chat> repoPage = new PageImpl<>(List.of(chat), PageRequest.of(0, 30), 1);
        given(chatRepository.findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), any(Pageable.class))).willReturn(repoPage);

        PageResponse<MessageResponse> result = chatService.getHistory(ROOM_UID, 0);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatRepository).findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
                eq(room), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(30);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).content()).isEqualTo("내용");
    }

    @Test
    @DisplayName("[AC-CHAT-18-4] getHistory()는 없거나 삭제된 방이면 404를 던진다")
    void getHistory_missingRoom_throwsChatroomNotFound() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.getHistory("nope", 0))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
    }

    // ---------- reportMessage ----------

    @Test
    @DisplayName("[AC-CHAT-20-1] reportMessage()는 타인의 정상 메시지를 즉시 blind=true로 전환한다")
    void reportMessage_normalMessage_setsBlindTrue() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        chatService.reportMessage(ROOM_UID, 42L, 2L); // 신고자(2L) != 작성자(1L)

        assertThat(chat.isBlind()).isTrue();
    }

    @Test
    @DisplayName("[AC-CHAT-27-1] reportMessage()는 자기 메시지를 신고하면 403을 던지고 blind 상태를 바꾸지 않는다")
    void reportMessage_selfReport_throwsAndKeepsBlindFalse() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 1L)) // 신고자 == 작성자
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SELF_REPORT_NOT_ALLOWED);
        assertThat(chat.isBlind()).isFalse();
    }

    @Test
    @DisplayName("[AC-CHAT-28-1] reportMessage()는 이미 blind인 메시지를 재신고해도 예외 없이 멱등하게 유지한다")
    void reportMessage_alreadyBlindMessage_isIdempotent() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        chat.blind(); // 이미 신고돼 blind 상태
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatCode(() -> chatService.reportMessage(ROOM_UID, 42L, 2L)).doesNotThrowAnyException();
        assertThat(chat.isBlind()).isTrue();
    }

    @Test
    @DisplayName("[AC-CHAT-29-1] reportMessage()는 이미 소프트삭제된 메시지를 신고하면 404를 던진다")
    void reportMessage_deletedMessage_throwsChatMessageNotFound() {
        Chatroom room = activeRoom(ROOM_UID);
        UserAccount author = userAccountWithId(1L, "작성자");
        Chat chat = chatOf(room, author, "내용");
        chat.delete(LocalDateTime.now());
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(42L, room)).willReturn(Optional.of(chat));

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("[AC-CHAT-20-2] reportMessage()는 방 안에서 messageId를 찾지 못하면 404를 던진다")
    void reportMessage_messageNotFoundInRoom_throwsChatMessageNotFound() {
        Chatroom room = activeRoom(ROOM_UID);
        given(chatroomRepository.findByUidAndDeletedAtIsNull(ROOM_UID)).willReturn(Optional.of(room));
        given(chatRepository.findByIdAndChatroom(999L, room)).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reportMessage(ROOM_UID, 999L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("reportMessage()는 방이 없으면 404를 던지고 메시지 조회 자체를 시도하지 않는다")
    void reportMessage_roomNotFound_throwsWithoutLookingUpMessage() {
        given(chatroomRepository.findByUidAndDeletedAtIsNull("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.reportMessage("nope", 42L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHATROOM_NOT_FOUND);
        verify(chatRepository, never()).findByIdAndChatroom(any(), any());
    }
}
