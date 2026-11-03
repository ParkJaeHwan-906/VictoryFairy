package com.skhynix.chat.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skhynix.chat.shared.redis.LikeSignal;
import com.skhynix.chat.shared.redis.LikeSignalCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.ObjectMapper;

/** chat:likes 수신 처리와 구독 수명주기. Redis 는 쓰지 않는다 — 실동작은 통합 테스트(ChatLikesRedisOutageIT 등)가 본다. */
class LikesSubscriberTest {

    private LikesThrottler throttler;
    private RedisConnectionFactory connectionFactory;
    private LikesSubscriber subscriber;

    @BeforeEach
    void setUp() {
        throttler = mock(LikesThrottler.class);
        connectionFactory = mock(RedisConnectionFactory.class);
        subscriber = new LikesSubscriber(connectionFactory, new LikeSignalCodec(new ObjectMapper()), throttler);
    }

    @AfterEach
    void tearDown() {
        subscriber.stop();
    }

    private void receive(String body) {
        subscriber.onMessage(new DefaultMessage("chat:likes".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }

    @Test
    @DisplayName("[CHAT-LK-24] 정상 메시지는 gameId 와 teamCode 를 스로틀러로 넘긴다")
    void validMessage_forwardedToThrottler() {
        receive("{\"gameId\":\"20261009HTLG0\",\"teamCode\":\"HT\"}");

        verify(throttler).offer("20261009HTLG0", "HT");
    }

    @ParameterizedTest(name = "[{index}] 깨진 메시지 {0}")
    @ValueSource(strings = {"garbage", "", "{\"gameId\":\"G\",\"teamCode\":1}", "{\"gameId\":\"G\"}", "[1,2]", "null"})
    @DisplayName("[CHAT-LK-37] 형식에 맞지 않는 메시지는 예외 없이 버려지고 스로틀러에 닿지 않으며, 다음 정상 메시지는 그대로 전달된다")
    void malformedMessage_droppedThenNextValidDelivered(String body) {
        receive(body);

        verifyNoInteractions(throttler);

        receive("{\"gameId\":\"G1\",\"teamCode\":\"LG\"}");
        verify(throttler, times(1)).offer("G1", "LG");
        verify(throttler, never()).offer(anyString(), org.mockito.ArgumentMatchers.eq(body));
    }

    @Test
    @DisplayName("[CHAT-LK-37] 깨진 메시지가 연달아 와도 구독은 멈추지 않고 정상 메시지만 순서대로 전달된다")
    void interleavedGarbageAndValid() {
        receive("garbage");
        receive("{\"gameId\":\"A\",\"teamCode\":\"HT\"}");
        receive("");
        receive("{\"gameId\":\"B\",\"teamCode\":5}");
        receive("{\"gameId\":\"C\",\"teamCode\":\"OB\"}");

        verify(throttler).offer("A", "HT");
        verify(throttler).offer("C", "OB");
        verify(throttler, times(2)).offer(anyString(), anyString());
    }

    @Test
    @DisplayName("[CHAT-LK-50] Redis 에 붙을 수 없어도 start() 는 막히지 않고 즉시 돌아오며(앱 기동이 실패하지 않는다) stop() 으로 깔끔히 멈춘다")
    void redisUnreachable_startDoesNotBlockOrThrow() {
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("refused"));

        long startedAt = System.nanoTime();
        subscriber.start();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs).as("start() 소요").isLessThan(500);
        assertThat(subscriber.isRunning()).isTrue();

        long stopStart = System.nanoTime();
        subscriber.stop();
        assertThat(subscriber.isRunning()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - stopStart)).as("stop() 소요").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("[CHAT-LK-24] stop 뒤 다시 start 할 수 있다 (lifecycle 빈은 재시작 가능해야 한다)")
    void restartable() {
        when(connectionFactory.getConnection()).thenThrow(new RedisConnectionFailureException("refused"));

        subscriber.start();
        subscriber.stop();
        subscriber.start();

        assertThat(subscriber.isRunning()).isTrue();
    }

    @Test
    @DisplayName("[CHAT-LK-24] 수신원이 가장 먼저 멈추도록 게이트웨이 컨슈머와 같은 단계다")
    void phaseMatchesGatewayConsumer() {
        assertThat(subscriber.getPhase()).isEqualTo(GatewayConsumer.PHASE);
        assertThat(LikeSignal.CHANNEL).isEqualTo("chat:likes");
    }
}
