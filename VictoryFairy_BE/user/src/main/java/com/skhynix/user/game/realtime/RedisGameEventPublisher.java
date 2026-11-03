package com.skhynix.user.game.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 채널로 발행하면 <b>발행한 파드를 포함한</b> 모든 user-app 파드의 {@link GameEventSubscriber}가 받아
 * 자기 레지스트리로 넘긴다 — 그래서 이 클래스는 로컬 레지스트리로 직접 전달하지 <b>않는다</b>(직접 전달까지
 * 하면 같은 파드 구독자에게 두 번 간다). quiz 의 {@code RedisPubSubPublisher}와 같은 구조다.
 *
 * <p>SQS 메시지는 파드 하나만 받는다(큐는 pub/sub 이 아니다). HPA 로 파드가 2개면 나머지 파드의 구독자는
 * 이 발행이 없으면 갱신을 못 받는다 — 그게 이 경로가 필요한 이유다.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class RedisGameEventPublisher implements GameEventPublisher {

    /** 채팅의 {@code realtime:events}와 채널을 나눈다 — 두 앱의 구독 컨테이너가 서로의 페이로드를 받지 않게. */
    static final String CHANNEL = "game:events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(GameUpdateEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("경기 갱신 이벤트 발행 실패 gameId={}", event.game().gameId(), e);
        }
    }
}
