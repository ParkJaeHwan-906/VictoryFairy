package com.skhynix.quiz.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.quiz.chat.dto.MessageEvent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수신 측 계약 + 발행→수신 왕복 등가성. 다른 파드가 보낸 이벤트가 이 파드에서 <b>같은 인스턴스 직접 전달과
 * 구분되지 않는 형태</b>로 레지스트리에 도달해야 SSE 프레임이 동일해진다.
 */
@ExtendWith(MockitoExtension.class)
class RealtimeEventSubscriberTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Mock
    private SseEmitterRegistry registry;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("[왕복] 발행한 이벤트가 수신 측에서 같은 roomUid·이벤트명·제외대상·payload 로 레지스트리에 닿는다")
    void roundTrip_deliversEquivalentEventToRegistry() {
        MessageEvent data = new MessageEvent(42L, "안녕", "닉", "user-profile-img/42.jpg",
                LocalDateTime.of(2026, 7, 27, 12, 0), "room-1");
        new RedisPubSubPublisher(redisTemplate, objectMapper)
                .publish("room-1", new RealtimeEvent("message", data, 7L));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(anyString(), payload.capture());

        new RealtimeEventSubscriber(registry, objectMapper).onMessage(message(payload.getValue()), null);

        ArgumentCaptor<RealtimeEvent> delivered = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(registry).publish(anyString(), delivered.capture());
        RealtimeEvent event = delivered.getValue();
        assertThat(event.name()).isEqualTo("message");
        assertThat(event.excludeUserAccountId()).isEqualTo(7L);
        // data 는 타입 없이 Map 으로 되살아나고, SSE 로 다시 쓰일 때 원본과 같은 JSON 이 된다.
        assertThat(event.data()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> receivedData = (Map<String, Object>) event.data();
        assertThat(receivedData).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", 42,
                "content", "안녕",
                "senderNickname", "닉",
                "profileImgUrl", "user-profile-img/42.jpg",
                "createdAt", "2026-07-27T12:00:00",
                "roomUid", "room-1"));
    }

    @Test
    @DisplayName("수신한 이벤트는 payload 의 roomUid 로 전달된다(구독 중이 아닌 방이면 레지스트리가 무시)")
    void onMessage_usesRoomUidFromPayload() {
        String json = """
                {"roomUid":"room-9","name":"message","data":{"content":"hi"},"excludeUserAccountId":null}""";

        new RealtimeEventSubscriber(registry, objectMapper).onMessage(message(json), null);

        verify(registry).publish(org.mockito.ArgumentMatchers.eq("room-9"), any(RealtimeEvent.class));
    }

    @Test
    @DisplayName("깨진 payload 는 삼키고 전달하지 않는다 — 한 건이 리스너 컨테이너를 멈추게 하지 않는다")
    void onMessage_swallowsMalformedPayload() {
        RealtimeEventSubscriber subscriber = new RealtimeEventSubscriber(registry, objectMapper);

        assertThatCode(() -> subscriber.onMessage(message("not-json"), null)).doesNotThrowAnyException();
        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-28/29] 종료 신호(subscription-close)는 registry.publish가 아니라 handleCloseCommand로 "
            + "위임되고, Map으로 역직렬화된 payload가 convertValue로 SubscriptionCloseCommand로 정확히 복원된다"
            + "(종료 신호가 구독자에게 data:로 새지 않는다는 계약을 JSON 왕복까지 태워 검증)")
    void onMessage_closeSignal_delegatesToHandleCloseCommandWithRestoredCommand() {
        SubscriptionCloseCommand command = SubscriptionCloseCommand.leave(7L, "origin-instance-1");
        new RedisPubSubPublisher(redisTemplate, objectMapper).publish("room-1", command.toEvent());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(anyString(), payload.capture());

        new RealtimeEventSubscriber(registry, objectMapper).onMessage(message(payload.getValue()), null);

        ArgumentCaptor<SubscriptionCloseCommand> delivered = ArgumentCaptor.forClass(SubscriptionCloseCommand.class);
        verify(registry).handleCloseCommand(eq("room-1"), delivered.capture());
        assertThat(delivered.getValue()).isEqualTo(command);
        verify(registry, never()).publish(anyString(), any());
    }

    private Message message(String body) {
        return new DefaultMessage(RedisPubSubPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }
}
