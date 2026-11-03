package com.skhynix.quiz.quizroom.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import com.skhynix.quiz.quizroom.realtime.QuizRoomPresenceTracker;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry.Subscription;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SubscriptionCloseCommand;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 퀴즈방 구독·퇴장. 구독은 {@link QuizRoomSseRegistry} 에만 들어간다 — 구단 채팅 레지스트리를 쓰면 그쪽 축출이
 * 같은 계정의 {@code /rt/chat} 스트림을 끊는다.
 *
 * <p>트랜잭션을 열지 않는다(SSE 응답 내내 커넥션을 잡지 않게). DB 조회는 리포지토리·리더가 각자 짧게 연다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizRoomSubscriptionService {

    private final QuizRoomRepository roomRepository;
    private final QuizRoomSseRegistry registry;
    private final QuizRoomPresenceTracker presenceTracker;
    private final QuizRoomVoteReader voteReader;
    private final RealtimeEventPublisher eventPublisher;

    /**
     * 순서: 방 확인(404면 스트림을 열지 않는다) → 등록(이 파드의 같은 계정 다른 퀴즈방 구독 축출) → 인원 등록 + 첫
     * {@code presence} → 문항별 {@code votes} → 다른 파드 축출 명령.
     *
     * <p>분포 스냅샷을 등록 <b>뒤</b>에 읽는다 — 앞에서 읽으면 그 사이 커밋된 답의 이벤트를 이 구독이 놓친다.
     * 축출 명령이 인원 등록 뒤인 이유는 {@link QuizRoomPresenceTracker#joined} 참고.
     */
    public SseEmitter subscribe(long roomId, Long userAccountId) {
        if (!roomRepository.existsById(roomId)) {
            throw new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND);
        }
        Subscription subscription = registry.register(roomId, userAccountId);
        try {
            presenceTracker.joined(subscription);
            List<VotesPayload> votes = voteReader.readRoom(roomId);
            for (VotesPayload payload : votes) {
                if (!registry.send(subscription, QuizRoomAnswerService.VOTES_EVENT, payload)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // 응답으로 나가지 못한 emitter 를 맵에 남기지 않는다
            registry.close(subscription);
            throw e;
        }
        publishQuietly(roomId, SubscriptionCloseCommand.evict(userAccountId, registry.instanceId()));
        return subscription.emitter();
    }

    /** 전면 멱등 — 구독 유무·방 존재와 무관하게 성공한다. */
    public void unsubscribe(long roomId, Long userAccountId) {
        registry.closeSubscriptions(roomId, userAccountId);
        publishQuietly(roomId, SubscriptionCloseCommand.leave(userAccountId, registry.instanceId()));
    }

    private void publishQuietly(long roomId, SubscriptionCloseCommand command) {
        try {
            eventPublisher.publish(QuizRoomSseRegistry.topic(roomId), command.toEvent());
        } catch (Exception e) {
            log.warn("퀴즈방 구독 종료 명령 발행 실패 roomId={}", roomId, e);
        }
    }
}
