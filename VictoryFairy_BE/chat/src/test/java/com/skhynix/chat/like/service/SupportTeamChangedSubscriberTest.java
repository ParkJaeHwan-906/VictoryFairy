package com.skhynix.chat.like.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

/** support:team-changed 수신 처리(LK-53·57·59). Redis 연결은 쓰지 않는다 — 구독 자체는 통합 테스트가 본다. */
class SupportTeamChangedSubscriberTest {

    private static final byte[] CHANNEL = SupportTeamChangedSubscriber.CHANNEL.getBytes(StandardCharsets.UTF_8);

    private SupportTeamCodeCache cache;
    private SupportTeamChangedSubscriber subscriber;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        cache = mock(SupportTeamCodeCache.class);
        subscriber = new SupportTeamChangedSubscriber(
                mock(RedisConnectionFactory.class), JsonMapper.builder().build(), cache);
        logs = new ListAppender<>();
        logs.start();
        logger = (Logger) LoggerFactory.getLogger(SupportTeamChangedSubscriber.class);
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void receive(String body) {
        subscriber.onMessage(new DefaultMessage(CHANNEL, bytes(body)), null);
    }

    @Test
    @DisplayName("[CHAT-LK-53] 정상 메시지는 userAccountId 항목만 무효화한다")
    void validMessage_invalidatesThatUser() {
        receive("{\"userAccountId\":42}");

        verify(cache).invalidate(42L);
        verify(cache, never()).invalidateAll();
    }

    @Test
    @DisplayName("[CHAT-LK-53] 알 수 없는 필드가 함께 있어도 userAccountId 로 무효화한다")
    void extraFieldsIgnored() {
        receive("{\"userAccountId\":5,\"extra\":\"x\"}");

        verify(cache).invalidate(5L);
    }

    @Test
    @DisplayName("[CHAT-LK-53] 정상 메시지는 WARN 이상 로그를 남기지 않는다")
    void validMessage_noWarn() {
        receive("{\"userAccountId\":42}");

        assertThat(logs.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "garbage",
            "",
            "[]",
            "123",
            "null",
            "{}",
            "{\"other\":1}",
            "{\"userAccountId\":null}",
            "{\"userAccountId\":\"abc\"}",
            "{\"userAccountId\":\"123\"}",
            "{\"userAccountId\":1.5}",
            "{\"userAccountId\":1.0}",
            "{\"userAccountId\":-1}",
            "{\"userAccountId\":0}",
            "{\"userAccountId\":99999999999999999999}",
            "{\"userAccountId\":true}",
            "{\"userAccountId\":[1]}",
            "{\"userAccountId\":"
    })
    @DisplayName("[CHAT-LK-57] 형식 위반 메시지는 무효화(단건·전체)를 일으키지 않고 WARN 1줄만 남기며 예외를 밖으로 던지지 않는다")
    void malformedMessage_droppedWithWarn(String body) {
        receive(body);

        verifyNoInteractions(cache);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    }

    @Test
    @DisplayName("[CHAT-LK-57] 깨진 메시지 뒤의 정상 메시지는 평소대로 처리된다 — 구독이 계속된다")
    void validMessageAfterMalformed_stillProcessed() {
        receive("garbage");
        receive("{\"userAccountId\":-1}");
        receive("{\"userAccountId\":8}");

        verify(cache).invalidate(8L);
        verify(cache, times(1)).invalidate(anyLong());
        verify(cache, never()).invalidateAll();
    }

    @Test
    @DisplayName("[CHAT-LK-57] parseUserAccountId 는 양의 정수(long 범위)를 그대로 돌려준다")
    void parse_validValues() {
        assertThat(subscriber.parseUserAccountId(bytes("{\"userAccountId\":1}"))).isEqualTo(1L);
        assertThat(subscriber.parseUserAccountId(bytes("{\"userAccountId\":9223372036854775807}")))
                .isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "garbage", "{}", "{\"userAccountId\":\"1\"}", "{\"userAccountId\":1.5}", "{\"userAccountId\":-3}",
            "{\"userAccountId\":0}", "{\"userAccountId\":9223372036854775808}"
    })
    @DisplayName("[CHAT-LK-57] parseUserAccountId 는 형식 위반에 IllegalArgumentException 을 던진다")
    void parse_invalidValues(String body) {
        assertThatThrownBy(() -> subscriber.parseUserAccountId(bytes(body)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[CHAT-LK-59] 구독이 맺어질 때마다 캐시 전체를 비운다 — 재구독도 같은 콜백이다")
    void channelSubscribed_clearsAll() {
        subscriber.onChannelSubscribed(CHANNEL, 1);
        subscriber.onChannelSubscribed(CHANNEL, 1);

        verify(cache, times(2)).invalidateAll();
        verify(cache, never()).invalidate(anyLong());
    }

    @Test
    @DisplayName("구독 단계(phase)는 LikesSubscriber 와 같다")
    void phase_sameAsLikesSubscriber() {
        assertThat(subscriber.getPhase()).isEqualTo(Integer.MAX_VALUE - 10);
        assertThat(subscriber.isRunning()).isFalse();
    }
}
