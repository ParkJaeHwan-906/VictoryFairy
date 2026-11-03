package com.skhynix.user.game.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis {@code game:events} 채널 수신 → 이 파드의 레지스트리로 팬아웃. */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class GameEventSubscriber implements MessageListener {

    private final GameSseRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // StringRedisTemplate 은 값을 UTF-8 문자열로 직렬화하므로 body 는 JSON 원문 그대로다.
            registry.publish(objectMapper.readValue(message.getBody(), GameUpdateEvent.class));
        } catch (Exception e) {
            log.warn("경기 갱신 이벤트 수신 처리 실패", e);
        }
    }
}
