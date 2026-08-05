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
            RealtimeEvent event = received.toEvent();

            // 종료 신호는 전송이 아니라 연결 종료로 갈라진다 — 이 분기가 없으면 종료 명령이 구독자에게
            // data: 로 그대로 나간다. payload 는 역직렬화로 Map 이 되어 있어 명령 타입으로 되돌린다.
            if (SubscriptionCloseCommand.isCloseSignal(event)) {
                registry.handleCloseCommand(received.roomUid(),
                        objectMapper.convertValue(event.data(), SubscriptionCloseCommand.class));
                return;
            }
            registry.publish(received.roomUid(), event);
        } catch (Exception e) {
            log.warn("실시간 이벤트 수신 처리 실패", e);
        }
    }
}
