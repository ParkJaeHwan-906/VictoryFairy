package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 채널로 발행하면 <b>발행한 인스턴스를 포함한</b> 모든 인스턴스의 {@link RealtimeEventSubscriber}가
 * 받아 자기 레지스트리로 넘긴다 — 그래서 이 클래스는 로컬 레지스트리로 직접 전달하지 <b>않는다</b>
 * (직접 전달까지 하면 같은 파드 구독자에게 두 번 간다).
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class RedisPubSubPublisher implements RealtimeEventPublisher {

    /**
     * 방마다 채널을 파지 않고 단일 채널에 {@code roomUid}를 실어 보낸다 — 방별 채널은 구독/해지를 방
     * 수명에 맞춰 관리해야 하는데, {@link SseEmitterRegistry#publish}가 모르는 방이면 맵 조회 1회로
     * 바로 반환하므로 그럴 이득이 없다.
     */
    static final String CHANNEL = "realtime:events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String roomUid, RealtimeEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(RealtimeEventMessage.of(roomUid, event));
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("실시간 이벤트 발행 실패 roomUid={} event={}", roomUid, event.name(), e);
        }
    }
}
