package com.skhynix.quiz.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link InMemoryPublisher}는 같은 프로세스 안의 {@link SseEmitterRegistry}로 그대로 위임하기만 하는
 * 얇은 어댑터라는 계약을 고정한다. 단, 종료 신호({@link SubscriptionCloseCommand})는 전송이 아니라
 * 연결 종료라 {@code publish}가 아닌 {@code handleCloseCommand}로 갈라져야 한다(QUIZ-CTAC-28/29) —
 * 이 분기가 없으면 종료 명령이 구독자에게 {@code data:}로 그대로 새어 나간다.
 */
@ExtendWith(MockitoExtension.class)
class InMemoryPublisherTest {

    @Mock
    private SseEmitterRegistry registry;

    @InjectMocks
    private InMemoryPublisher publisher;

    @Test
    @DisplayName("[AC-CHAT-16-2] publish()를 호출하면 같은 roomUid·event 그대로 SseEmitterRegistry에 위임한다"
            + "(단일 인스턴스 전달)")
    void publish_delegatesToRegistryWithSameRoomAndEvent() {
        RealtimeEvent event = new RealtimeEvent("message", "payload", 1L);

        publisher.publish("room-1", event);

        verify(registry).publish("room-1", event);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-28/29] 종료 신호(subscription-close)를 publish()하면 registry.publish가 아니라 "
            + "handleCloseCommand로 위임한다 — 종료 신호가 구독자에게 data:로 새지 않는다")
    void publish_closeSignal_delegatesToHandleCloseCommandNotPublish() {
        SubscriptionCloseCommand command = SubscriptionCloseCommand.leave(1L, "origin-instance");
        RealtimeEvent event = command.toEvent();

        publisher.publish("room-1", event);

        verify(registry).handleCloseCommand("room-1", command);
        verify(registry, never()).publish(anyString(), any());
    }
}
