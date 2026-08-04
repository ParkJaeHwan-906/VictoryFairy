package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 같은 프로세스 안의 구독자에게 직접 전달하는 {@link RealtimeEventPublisher} 구현({@code prod} 이외 전용).
 * 인스턴스가 하나뿐인 로컬·테스트에서 완결되며, 로컬 개발에 Redis가 필요 없다. 운영은 파드가 여러 개일
 * 수 있어 {@link RedisPubSubPublisher}가 그 자리를 대신한다.
 */
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class InMemoryPublisher implements RealtimeEventPublisher {

    private final SseEmitterRegistry registry;

    @Override
    public void publish(String roomUid, RealtimeEvent event) {
        registry.publish(roomUid, event);
    }
}
