package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 같은 프로세스 안의 구독자에게 직접 전달하는 기본 {@link RealtimeEventPublisher} 구현.
 * {@code @Profile}/조건 없이 항상 등록되며, 단일 인스턴스 환경(로컬·테스트·현재 운영)에서 완결된다.
 * 다중 인스턴스 fan-out이 필요해지면 Redis 구현을 추가로 등록한다(포트 Javadoc 참고).
 */
@Component
@RequiredArgsConstructor
public class InMemoryPublisher implements RealtimeEventPublisher {

    private final SseEmitterRegistry registry;

    @Override
    public void publish(String roomUid, RealtimeEvent event) {
        registry.publish(roomUid, event);
    }
}
