package com.skhynix.quiz.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.quiz.chat.dto.MessageEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link RedisPubSubPublisher}의 계약: (1) 이벤트를 Redis 채널로만 내보내고 로컬 레지스트리는 건드리지
 * 않으며, (2) 발행 실패를 삼킨다(fire-and-forget).
 */
@ExtendWith(MockitoExtension.class)
class RedisPubSubPublisherTest {

    /** Boot 이 구성하는 ObjectMapper 와 같은 날짜 정책(ISO 문자열)으로 맞춘다. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SseEmitterRegistry registry;

    @Test
    @DisplayName("publish()는 roomUid·이벤트를 JSON 으로 직렬화해 단일 채널로 발행한다")
    void publish_sendsJsonToChannel() throws Exception {
        RedisPubSubPublisher publisher = new RedisPubSubPublisher(redisTemplate, objectMapper);
        MessageEvent data = new MessageEvent(42L, "안녕", "닉", LocalDateTime.of(2026, 7, 27, 12, 0), "room-1");

        publisher.publish("room-1", new RealtimeEvent("message", data, 7L));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(anyString(), payload.capture());
        JsonNode sent = objectMapper.readTree(payload.getValue());
        assertThat(sent.get("roomUid").asString()).isEqualTo("room-1");
        assertThat(sent.get("name").asString()).isEqualTo("message");
        assertThat(sent.get("excludeUserAccountId").asLong()).isEqualTo(7L);
        assertThat(sent.get("data").get("id").asLong()).isEqualTo(42L);
        assertThat(sent.get("data").get("content").asString()).isEqualTo("안녕");
        assertThat(sent.get("data").get("createdAt").asString()).isEqualTo("2026-07-27T12:00:00");
    }

    @Test
    @DisplayName("publish()는 로컬 레지스트리로 직접 전달하지 않는다 — 자기 인스턴스도 구독으로 되받으므로 "
            + "직접 전달까지 하면 같은 파드 구독자에게 두 번 간다")
    void publish_doesNotDeliverToLocalRegistryDirectly() {
        RedisPubSubPublisher publisher = new RedisPubSubPublisher(redisTemplate, objectMapper);

        publisher.publish("room-1", new RealtimeEvent("message", "payload", null));

        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("Redis 발행이 실패해도 예외를 밖으로 던지지 않는다(전달 실패가 저장·201 응답을 되돌리지 않음)")
    void publish_swallowsRedisFailure() {
        willThrow(new RuntimeException("redis down"))
                .given(redisTemplate).convertAndSend(anyString(), any());
        RedisPubSubPublisher publisher = new RedisPubSubPublisher(redisTemplate, objectMapper);

        assertThatCode(() -> publisher.publish("room-1", new RealtimeEvent("message", "payload", null)))
                .doesNotThrowAnyException();
    }
}
