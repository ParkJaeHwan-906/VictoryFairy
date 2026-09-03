package com.skhynix.quiz.chat.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageEvent;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.chat.profanity.ProfanityFilter;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import com.skhynix.quiz.realtime.SubscriptionCloseCommand;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    private static final int HISTORY_PAGE_SIZE = 30;

    private final ChatroomRepository chatroomRepository;
    private final ChatRepository chatRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final RealtimeEventPublisher eventPublisher;
    private final SseEmitterRegistry emitterRegistry;
    private final ProfanityFilter profanityFilter;

    public List<RoomResponse> getRooms(Long teamId, Long userAccountId) {
        Long supportTeamId = currentSupportTeamId(userAccountId);
        if (teamId != null && !supportTeamId.equals(teamId)) {
            // 존재하지 않는 구단 id도 여기서 끝난다 — 구단 조회로 404를 따로 내지 않는다
            throw new BusinessException(ErrorCode.CHATROOM_TEAM_MISMATCH);
        }
        return chatroomRepository.findAllByTeam_IdAndDeletedAtIsNull(supportTeamId).stream()
                .map(RoomResponse::of)
                .toList();
    }

    public RoomResponse getRoom(String roomUid, Long userAccountId) {
        return RoomResponse.of(findAccessibleRoom(roomUid, userAccountId));
    }

    public SseEmitter subscribe(String roomUid, Long userAccountId) {
        findAccessibleRoom(roomUid, userAccountId);

        SseEmitter emitter = emitterRegistry.register(roomUid, userAccountId);
        publishAfterCommit(roomUid,
                SubscriptionCloseCommand.evict(userAccountId, emitterRegistry.instanceId()).toEvent());
        return emitter;
    }

    public void unsubscribe(String roomUid, Long userAccountId) {
        emitterRegistry.closeSubscriptions(roomUid, userAccountId);
        publishAfterCommit(roomUid,
                SubscriptionCloseCommand.leave(userAccountId, emitterRegistry.instanceId()).toEvent());
    }

    @Transactional
    public MessageResponse sendMessage(String roomUid, Long senderId, String content) {
        AccessibleRoom access = findAccessibleRoomWithSupportTeam(roomUid, senderId);
        UserAccount sender = userAccountRepository.findById(senderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        // 마스킹은 엔티티 생성 이전에 끝낸다 — 엔티티가 필터를 부르면 domain 모듈이 필터 데이터에
        // 의존하게 되고, 필터를 쓰지 않는 경로(탈퇴자 메시지 이관 등)까지 영향권에 들어온다.
        // 실패는 삼키지 않는다: 원문을 그대로 저장하는 fallback 은 필터가 꺼진 줄 모른 채 욕설이
        // 저장되는 결과를 만든다.
        String masked = profanityFilter.mask(content, access.supportTeam().getCode());

        Chat chat = chatRepository.saveAndFlush(Chat.builder()
                .chatroom(access.room())
                .userAccount(sender)
                .content(masked)
                .build());

        publishMessage(roomUid, chat, senderId);
        return MessageResponse.from(chat);
    }

    public PageResponse<MessageResponse> getHistory(String roomUid, int page, Long userAccountId) {
        Chatroom room = findAccessibleRoom(roomUid, userAccountId);
        Pageable pageable = PageRequest.of(page, HISTORY_PAGE_SIZE);
        Page<MessageResponse> result = chatRepository
                .findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(room, pageable)
                .map(MessageResponse::from);
        return PageResponse.from(result);
    }

    @Transactional
    public void reportMessage(String roomUid, Long messageId, Long reporterId) {
        Chatroom room = findAccessibleRoom(roomUid, reporterId);
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

    private Chatroom findAccessibleRoom(String roomUid, Long userAccountId) {
        Chatroom room = findActiveRoom(roomUid);
        Long supportTeamId = currentSupportTeamId(userAccountId);
        // LAZY 프록시의 식별자는 FK 그대로라 여기서 팀 로딩이 일어나지 않는다.
        if (!room.getTeam().getId().equals(supportTeamId)) {
            throw new BusinessException(ErrorCode.CHATROOM_TEAM_MISMATCH);
        }
        return room;
    }

    /**
     * 전송 경로 전용 접근 검사. {@link #findAccessibleRoom} 과 판정·순서(404→400→403)는 같고, 응원 구단을
     * id 가 아니라 엔티티로 돌려준다 — 치환어 결정에 {@code teams.code} 가 필요하기 때문이다.
     *
     * <p>구단 조회를 {@code @EntityGraph} 판으로 바꿔 join 한 번에 끝낸다. LAZY 프록시에서 {@code code} 를
     * 읽으면 고빈도 전송 경로에 SELECT 가 한 번 더 붙는다.
     */
    private AccessibleRoom findAccessibleRoomWithSupportTeam(String roomUid, Long userAccountId) {
        Chatroom room = findActiveRoom(roomUid);
        Team supportTeam = userSupportTeamRepository
                .findWithTeamByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(UserSupportTeam::getTeam)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));
        if (!room.getTeam().getId().equals(supportTeam.getId())) {
            throw new BusinessException(ErrorCode.CHATROOM_TEAM_MISMATCH);
        }
        return new AccessibleRoom(room, supportTeam);
    }

    private record AccessibleRoom(Chatroom room, Team supportTeam) {
    }

    private Long currentSupportTeamId(Long userAccountId) {
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> support.getTeam().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));
    }

    private void publishMessage(String roomUid, Chat chat, Long senderId) {
        MessageEvent payload = MessageEvent.of(chat, roomUid);
        publishAfterCommit(roomUid, new RealtimeEvent("message", payload, senderId));
    }

    private void publishAfterCommit(String roomUid, RealtimeEvent event) {
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
        // fire-and-forget: 전달 실패가 저장·응답을 되돌리지 않도록 삼킨다(히스토리로 복구 가능).
        try {
            eventPublisher.publish(roomUid, event);
        } catch (Exception ignored) {
        }
    }
}
