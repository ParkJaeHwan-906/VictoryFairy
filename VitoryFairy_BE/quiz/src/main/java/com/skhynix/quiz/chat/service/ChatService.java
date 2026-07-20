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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 채팅 도메인 비즈니스 로직: 방 조회·구독·전송·히스토리·신고.
 *
 * <p>participants는 {@link SseEmitterRegistry}의 현재 구독 수로 서빙한다(best-effort). connect/disconnect
 * 마다 {@code chatrooms.participants} 컬럼을 갱신하면 write가 폭주하므로, 단일 인스턴스에서 정확한
 * 인메모리 카운트를 채택했다. {@code Chatroom.join()/leave()}와 {@code participants} 컬럼은 그대로
 * 존재하지만 이번 범위에서 쓰지 않는다(다중 인스턴스 전역 집계는 후속).
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
     * 소프트 삭제되지 않은 방 목록. participants는 각 방의 현재 구독 수로 채운다.
     */
    public List<RoomResponse> getRooms() {
        return chatroomRepository.findAllByDeletedAtIsNull().stream()
                .map(room -> RoomResponse.of(room, emitterRegistry.count(room.getUid())))
                .toList();
    }

    /**
     * 방 상세. 없거나 소프트 삭제된 방이면 404.
     */
    public RoomResponse getRoom(String roomUid) {
        Chatroom room = findActiveRoom(roomUid);
        return RoomResponse.of(room, emitterRegistry.count(roomUid));
    }

    /**
     * SSE 구독을 연다. 없거나 삭제된 방이면 404. 구독 성립 시 participants(구독 수)가 1 증가하고,
     * 연결 종료 시 감소한다(레지스트리 콜백).
     */
    public SseEmitter subscribe(String roomUid, Long userAccountId) {
        findActiveRoom(roomUid);
        return emitterRegistry.register(roomUid, userAccountId);
    }

    /**
     * 메시지를 저장하고 발신자를 제외한 같은 방 구독자에게 전달한다. 전달은 fire-and-forget이라 발행
     * 실패가 저장·응답 성공을 되돌리지 않는다.
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

    private void publishMessage(String roomUid, Chat chat, Long senderId) {
        // fire-and-forget: 전달 실패가 저장·응답을 되돌리지 않도록 삼킨다(히스토리로 복구 가능).
        try {
            MessageEvent payload = MessageEvent.of(chat, roomUid);
            eventPublisher.publish(roomUid, new RealtimeEvent("message", payload, senderId));
        } catch (Exception ignored) {
            // 전달 실패는 무시한다(QUIZ-CHAT-17).
        }
    }
}
