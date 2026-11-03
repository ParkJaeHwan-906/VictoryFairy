package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
@RequiredArgsConstructor
public class InMemoryPublisher implements RealtimeEventPublisher {

    private final SseEmitterRegistry registry;
    private final QuizRoomSseRegistry quizRoomRegistry;

    @Override
    public void publish(String roomUid, RealtimeEvent event) {
        // 퀴즈방 토픽은 채팅 레지스트리에 닿지 않게 먼저 가른다(QuizRoomSseRegistry javadoc).
        if (QuizRoomSseRegistry.isTopic(roomUid)) {
            quizRoomRegistry.deliver(roomUid, event, SubscriptionCloseCommand.isCloseSignal(event)
                    ? (SubscriptionCloseCommand) event.data() : null);
            return;
        }
        // 종료 신호는 전송이 아니라 연결 종료다 — 갈라내지 않으면 구독자에게 data: 로 흘러간다.
        if (SubscriptionCloseCommand.isCloseSignal(event)) {
            registry.handleCloseCommand(roomUid, (SubscriptionCloseCommand) event.data());
            return;
        }
        registry.publish(roomUid, event);
    }
}
