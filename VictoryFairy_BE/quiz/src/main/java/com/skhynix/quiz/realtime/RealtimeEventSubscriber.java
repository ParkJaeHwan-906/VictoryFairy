package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis 채널({@link RedisPubSubPublisher#CHANNEL})을 듣고 이 인스턴스의 구독자에게 전달하는 수신 측
 * ({@code prod} 전용). 등록은 {@link RealtimeRedisConfig}가 한다.
 *
 * <p>깨진 payload 하나가 리스너 컨테이너를 흔들지 않도록 예외는 여기서 삼키고 로그만 남긴다.
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class RealtimeEventSubscriber implements MessageListener {

    private final SseEmitterRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // StringRedisTemplate 은 값을 UTF-8 문자열로 직렬화하므로 body 는 JSON 원문 그대로다.
            RealtimeEventMessage received = objectMapper.readValue(message.getBody(), RealtimeEventMessage.class);
            registry.publish(received.roomUid(), received.toEvent());
        } catch (Exception e) {
            log.warn("실시간 이벤트 수신 처리 실패", e);
        }
    }
}
