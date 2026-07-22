package com.skhynix.quiz.realtime;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link InMemoryPublisher}는 같은 프로세스 안의 {@link SseEmitterRegistry}로 그대로 위임하기만 하는
 * 얇은 어댑터라는 계약을 고정한다.
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
}
