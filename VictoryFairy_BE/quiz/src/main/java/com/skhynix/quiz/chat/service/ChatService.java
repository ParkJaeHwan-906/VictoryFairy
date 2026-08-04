package com.skhynix.quiz.chat.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageEvent;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 채팅 도메인 비즈니스 로직: 방 조회·구독·전송·히스토리·신고.
 *
 * <p>방 조회 응답은 참여 인원을 노출하지 않는다 — 인메모리 구독 수도 DB 집계도 다중 파드에서 신뢰할
 * 값을 못 만든다(상세는 {@code .claude/modules/quiz.md}). {@code Chatroom.participants}는 domain에
 * 남아 있으나 여기서 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private static final int HISTORY_PAGE_SIZE = 30;

    private final ChatroomRepository chatroomRepository;
    private final ChatRepository chatRepository;
    private final UserAccountRepository userAccountRepository;
    private final RealtimeEventPublisher eventPublisher;
    private final SseEmitterRegistry emitterRegistry;

    /**
     * 소프트 삭제되지 않은 방 목록.
     */
    public List<RoomResponse> getRooms() {
        return chatroomRepository.findAllByDeletedAtIsNull().stream()
                .map(RoomResponse::of)
                .toList();
    }

    /**
     * 방 상세. 없거나 소프트 삭제된 방이면 404.
     */
    public RoomResponse getRoom(String roomUid) {
        return RoomResponse.of(findActiveRoom(roomUid));
    }

    /** SSE 구독을 연다. 없거나 삭제된 방이면 404. */
    public SseEmitter subscribe(String roomUid, Long userAccountId) {
        findActiveRoom(roomUid);
        return emitterRegistry.register(roomUid, userAccountId);
    }

    /**
     * 메시지를 저장하고 발신자를 제외한 같은 방 구독자에게 전달한다. 전달은 커밋 이후에 일어나며
     * fire-and-forget이라 발행 실패가 저장·응답 성공을 되돌리지 않는다({@link #publishMessage} 참고).
     */
    @Transactional
    public MessageResponse sendMessage(String roomUid, Long senderId, String content) {
        Chatroom room = findActiveRoom(roomUid);
        UserAccount sender = userAccountRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        Chat chat = chatRepository.saveAndFlush(Chat.builder()
                .chatroom(room)
                .userAccount(sender)
                .content(content)
                .build());

        publishMessage(roomUid, chat, senderId);
        return MessageResponse.from(chat);
    }

    /**
     * 방 히스토리(최신순 30건 페이징, blind·삭제 제외).
     */
    public PageResponse<MessageResponse> getHistory(String roomUid, int page) {
        Chatroom room = findActiveRoom(roomUid);
        Pageable pageable = PageRequest.of(page, HISTORY_PAGE_SIZE);
        Page<MessageResponse> result = chatRepository
                .findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(room, pageable)
                .map(MessageResponse::from);
        return PageResponse.from(result);
    }

    /**
     * 메시지 신고 → 즉시 blind(자동, 관리자 없음). 자기 신고 403, 삭제된 메시지 404, 이미 blind면 no-op.
     */
    @Transactional
    public void reportMessage(String roomUid, Long messageId, Long reporterId) {
        Chatroom room = findActiveRoom(roomUid);
        Chat chat = chatRepository.findByIdAndChatroom(messageId, room)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));

        if (chat.isDeleted()) {
            throw new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND);
        }
        if (chat.getUserAccount().getId().equals(reporterId)) {
            throw new BusinessException(ErrorCode.SELF_REPORT_NOT_ALLOWED);
        }
        // 이미 blind면 blind()가 값을 그대로 유지하므로 멱등(no-op)이다.
        chat.blind();
    }

    private Chatroom findActiveRoom(String roomUid) {
        return chatroomRepository.findByUidAndDeletedAtIsNull(roomUid)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));
    }

    /**
     * 저장 트랜잭션이 <b>커밋된 뒤에</b> 전달한다. 커밋 전에 발행하면 뒤이어 커밋이 실패했을 때 구독자가
     * DB에 없는 유령 메시지를 이미 받아버릴 수 있다 — afterCommit으로 미뤄 "전달된 것은 반드시 저장돼
     * 있다"를 보장한다. 동기화가 없는 호출(트랜잭션 밖)을 대비해 그 경우 즉시 발행으로 떨어진다.
     */
    private void publishMessage(String roomUid, Chat chat, Long senderId) {
        MessageEvent payload = MessageEvent.of(chat, roomUid);
        RealtimeEvent event = new RealtimeEvent("message", payload, senderId);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishNow(roomUid, event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishNow(roomUid, event);
            }
        });
    }

    private void publishNow(String roomUid, RealtimeEvent event) {
        // fire-and-forget: 전달 실패가 저장·응답을 되돌리지 않도록 삼킨다(QUIZ-CHAT-17, 히스토리로 복구 가능).
        try {
            eventPublisher.publish(roomUid, event);
        } catch (Exception ignored) {
        }
    }
}
