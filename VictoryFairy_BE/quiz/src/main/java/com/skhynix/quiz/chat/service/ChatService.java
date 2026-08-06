package com.skhynix.quiz.chat.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.chat.repository.ChatRepository;
import com.skhynix.domain.chat.repository.ChatroomRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.MessageEvent;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
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

/**
 * 채팅 도메인 비즈니스 로직: 방 조회·구독·퇴장·전송·히스토리·신고.
 *
 * <p>채팅방은 <b>응원 구단 단위 폐쇄 공간</b>이다 — 목록과 방 단위 경로 전부에서 요청자의 현재 응원 구단
 * ({@code user_support_team}의 {@code oppose is null} 1행)과 방의 구단이 같아야 한다. 판정 순서는
 * 404(방 존재) → 400(응원 구단 없음) → 403(구단 불일치)로 고정이다(상세: {@code docs/requirements/quiz/chat-team-access-control.md}).
 *
 * <p>방 조회 응답은 참여 인원을 노출하지 않는다 — 인메모리 구독 수도 DB 집계도 다중 파드에서 신뢰할
 * 값을 못 만든다(상세는 {@code .claude/modules/quiz.md}). {@code Chatroom.participants}는 domain에
 * 남아 있으나 여기서 쓰지 않는다(퇴장·축출도 이 값을 건드리지 않는다).
 */
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

    /**
     * 요청자의 응원 구단 방 목록(소프트 삭제 제외). {@code teamId}가 없으면 응원 구단으로 간주하고,
     * 응원 구단과 다르면 403이다 — 그래서 유효한 값은 사실상 하나뿐이며, 그럼에도 파라미터를 두는 이유는
     * 클라이언트의 잘못된 구단 상태가 조용히 무시되지 않고 403으로 드러나게 하기 위함이다.
     */
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

    /**
     * 방 상세. 없거나 소프트 삭제된 방이면 404, 내 응원 구단 방이 아니면 403.
     */
    public RoomResponse getRoom(String roomUid, Long userAccountId) {
        return RoomResponse.of(findAccessibleRoom(roomUid, userAccountId));
    }

    /**
     * SSE 구독을 연다. 없거나 삭제된 방이면 404, 내 응원 구단 방이 아니면 403.
     *
     * <p>구단 검사는 <b>스트림을 열기 전에</b> 이 트랜잭션 안에서 끝난다 — {@code Chatroom.team}은 LAZY이고
     * {@code open-in-view: false}라, 롱커넥션이 열린 뒤에 팀을 읽으면 SSE가 살아 있는 30분 내내 JPA
     * 커넥션을 붙들어 Hikari 풀이 고갈된다.
     *
     * <p>구독이 성립하면 같은 사용자의 기존 구독을 축출한다(last-one-wins). 로컬은 {@code register}가
     * 동기적으로 처리하고, 다른 파드는 커밋 뒤 버스로 나가는 종료 명령이 처리한다.
     */
    public SseEmitter subscribe(String roomUid, Long userAccountId) {
        findAccessibleRoom(roomUid, userAccountId);

        SseEmitter emitter = emitterRegistry.register(roomUid, userAccountId);
        publishAfterCommit(roomUid,
                SubscriptionCloseCommand.evict(userAccountId, emitterRegistry.instanceId()).toEvent());
        return emitter;
    }

    /**
     * 명시적 퇴장 — 그 사용자의 이 방 구독을 끊는다. 끊을 구독이 없어도 200이다(멱등).
     *
     * <p><b>가드를 걸지 않는 것이 계약이다</b>: 구단 일치 검사도, 응원 구단 존재 요구도,
     * 방 존재·비삭제 검사도 없다. 정리 요청을 403/400/404로 막으면 구단을 바꾼 사용자나 삭제된 방에 남은
     * 사용자가 자기 낡은 연결을 닫지 못해 그 연결이 최대 30분 살아남는다. 퇴장은 자기 연결만 건드리므로
     * 막아서 지킬 것도 없다.
     */
    public void unsubscribe(String roomUid, Long userAccountId) {
        emitterRegistry.closeSubscriptions(roomUid, userAccountId);
        publishAfterCommit(roomUid,
                SubscriptionCloseCommand.leave(userAccountId, emitterRegistry.instanceId()).toEvent());
    }

    /**
     * 메시지를 저장하고 발신자를 제외한 같은 방 구독자에게 전달한다. 전달은 커밋 이후에 일어나며
     * fire-and-forget이라 발행 실패가 저장·응답 성공을 되돌리지 않는다({@link #publishMessage} 참고).
     */
    @Transactional
    public MessageResponse sendMessage(String roomUid, Long senderId, String content) {
        Chatroom room = findAccessibleRoom(roomUid, senderId);
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
    public PageResponse<MessageResponse> getHistory(String roomUid, int page, Long userAccountId) {
        Chatroom room = findAccessibleRoom(roomUid, userAccountId);
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

    /**
     * 방을 찾고 요청자의 응원 구단 방인지까지 확인한다. 판정 순서가 계약이다 — 방 존재·비삭제(404)를
     * 구단 일치(403)보다 먼저 본다.
     */
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
     * 요청자의 현재 응원 구단 id. 없으면 비교 기준 자체가 없어 어떤 방도 "내 구단 방"이 아니므로 400이다.
     *
     * <p>"응원 구단 1개"는 스키마가 아니라 서비스 정책이라 이 조회는 정책이 깨진 데이터에서 예외를 던진다 —
     * 조용히 첫 행을 고르지 않는 것이 의도이며 quiz도 우회하지 않는다.
     */
    private Long currentSupportTeamId(Long userAccountId) {
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> support.getTeam().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPORT_TEAM_REQUIRED));
    }

    private void publishMessage(String roomUid, Chat chat, Long senderId) {
        MessageEvent payload = MessageEvent.of(chat, roomUid);
        publishAfterCommit(roomUid, new RealtimeEvent("message", payload, senderId));
    }

    /**
     * 트랜잭션이 <b>커밋된 뒤에</b> 발행한다. 커밋 전에 발행하면 뒤이어 커밋이 실패했을 때 구독자가
     * DB에 없는 유령 메시지를 이미 받아버릴 수 있다 — afterCommit으로 미뤄 "전달된 것은 반드시 저장돼
     * 있다"를 보장한다. 동기화가 없는 호출(트랜잭션 밖)을 대비해 그 경우 즉시 발행으로 떨어진다.
     *
     * <p>구독 종료 명령도 이 경로를 탄다 — 저장 순서 때문이 아니라, 커밋 전에 발행하면 Redis 왕복 동안
     * JPA 커넥션을 붙든 채로 기다리게 되기 때문이다.
     */
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
