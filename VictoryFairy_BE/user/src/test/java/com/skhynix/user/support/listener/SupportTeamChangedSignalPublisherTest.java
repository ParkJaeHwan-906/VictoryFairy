package com.skhynix.user.support.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.skhynix.user.support.event.SupportTeamChangedEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비동기 발행기 단위 테스트. sleep 을 쓰지 않는다 - 발행 스레드의 완료는 목의 {@link CountDownLatch}/로그 큐로
 * 기다리고, 단일 스레드 FIFO 라는 성질을 이용해 "뒤에 보낸 센티널이 처리됐다 = 앞의 것도 처리됐다"로 부재를 증명한다.
 * 요구사항: {@code docs/requirements/user/support-selection.md} USER-SP-47, 50, 51, 52.
 */
class SupportTeamChangedSignalPublisherTest {

    private static final long AWAIT_SECONDS = 5;
    // 구현의 QUEUE_CAPACITY(private) 와 같은 값. 바뀌면 이 테스트가 먼저 깨져 알려 준다.
    private static final int QUEUE_CAPACITY = 1000;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private SupportTeamChangedSignalPublisher publisher;

    private Logger logger;
    private QueueAppender appender;

    @BeforeEach
    void setUp() {
        publisher = new SupportTeamChangedSignalPublisher(redis, objectMapper);
        logger = (Logger) LoggerFactory.getLogger(SupportTeamChangedSignalPublisher.class);
        appender = new QueueAppender();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        publisher.shutdown();
    }

    static class QueueAppender extends AppenderBase<ILoggingEvent> {
        final BlockingQueue<ILoggingEvent> warns = new LinkedBlockingQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel() == Level.WARN) {
                warns.add(event);
            }
        }
    }

    @Test
    @DisplayName("[USER-SP-47] 채널 support:team-changed 에 userAccountId JSON 한 건을 발행한다")
    void publishesChannelAndPayload() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            published.countDown();
            return 1L;
        });

        publisher.onTeamChanged(new SupportTeamChangedEvent(42L));

        assertThat(published.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis, times(1)).convertAndSend(eq("support:team-changed"), payload.capture());
        assertThat(payload.getValue()).isEqualTo("{\"userAccountId\":42}");
    }

    @Test
    @DisplayName("[USER-SP-53] 발행기에 @Profile/@ConditionalOn* 이 없어 활성 프로파일과 무관하게 빈으로 등록된다(정적 검사만 - dev 기동 실측은 아님)")
    void publisher_hasNoProfileOrConditionRestriction() {
        Class<?> type = SupportTeamChangedSignalPublisher.class;

        assertThat(type.isAnnotationPresent(org.springframework.context.annotation.Profile.class)).isFalse();
        assertThat(java.util.Arrays.stream(type.getAnnotations()).map(a -> a.annotationType().getSimpleName()))
                .noneMatch(n -> n.startsWith("ConditionalOn") || n.equals("Conditional"));
    }

    @Test
    @DisplayName("[USER-SP-47] 발행은 호출 스레드가 아닌 전용 스레드(support-team-changed-publisher)에서 일어난다")
    void publishesOnDedicatedThread() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            threadName.set(Thread.currentThread().getName());
            published.countDown();
            return 1L;
        });

        publisher.onTeamChanged(new SupportTeamChangedEvent(1L));

        assertThat(published.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).isEqualTo("support-team-changed-publisher");
    }

    @Test
    @DisplayName("[USER-SP-50, USER-SP-51] Redis 발행이 예외를 던져도 호출자에게 전파하지 않고 userAccountId 를 담은 WARN 을 남긴다")
    void redisFailure_isNotPropagated_andLogsWarnWithAccountId() throws Exception {
        when(redis.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> publisher.onTeamChanged(new SupportTeamChangedEvent(77L))).doesNotThrowAnyException();

        ILoggingEvent warn = appender.warns.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(warn).isNotNull();
        assertThat(warn.getFormattedMessage()).contains("userAccountId=77");
    }

    @Test
    @DisplayName("[USER-SP-51] 실패한 신호는 재시도하지 않는다 - 뒤따른 센티널이 처리된 시점에도 실패 건의 발행 시도는 1회뿐이다")
    void failedSignal_isNotRetried() throws Exception {
        CountDownLatch sentinelDone = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            String payload = call.getArgument(1);
            if (payload.equals("{\"userAccountId\":1}")) {
                throw new IllegalStateException("redis down");
            }
            sentinelDone.countDown();
            return 1L;
        });

        publisher.onTeamChanged(new SupportTeamChangedEvent(1L));
        publisher.onTeamChanged(new SupportTeamChangedEvent(2L));

        assertThat(sentinelDone.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        verify(redis, times(1)).convertAndSend("support:team-changed", "{\"userAccountId\":1}");
        verify(redis, times(1)).convertAndSend("support:team-changed", "{\"userAccountId\":2}");
    }

    @Test
    @DisplayName("[USER-SP-50] 직렬화가 실패해도 전파하지 않고 WARN 만 남기며 Redis 는 호출하지 않는다")
    void serializationFailure_isNotPropagated() throws Exception {
        ObjectMapper broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JacksonException("boom") { });
        publisher.shutdown();
        publisher = new SupportTeamChangedSignalPublisher(redis, broken);

        assertThatCode(() -> publisher.onTeamChanged(new SupportTeamChangedEvent(5L))).doesNotThrowAnyException();

        ILoggingEvent warn = appender.warns.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(warn).isNotNull();
        assertThat(warn.getFormattedMessage()).contains("userAccountId=5");
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("[USER-SP-52] Redis 명령이 매달려도 onTeamChanged 는 발행 완료를 기다리지 않고 즉시 반환한다")
    void hangingRedis_doesNotBlockCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            release.await(AWAIT_SECONDS * 4, TimeUnit.SECONDS);
            return 1L;
        });

        // 호출이 발행 스레드의 대기와 엮여 있다면 release 를 풀기 전에는 이 줄이 돌아오지 못한다.
        publisher.onTeamChanged(new SupportTeamChangedEvent(9L));

        assertThat(started.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(release.getCount()).isEqualTo(1); // Redis 는 아직 매달린 채인데 호출자는 이미 돌아왔다
        release.countDown();
    }

    @Test
    @DisplayName("[USER-SP-52] 대기열(1000)이 가득 차면 넘친 신호는 버려지고 예외 없이 userAccountId 를 담은 WARN 만 남는다")
    void queueOverflow_dropsSignalWithWarnAndNoException() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            release.await(AWAIT_SECONDS * 4, TimeUnit.SECONDS);
            return 1L;
        });

        // 워커 1개가 첫 작업에 묶인 것을 확인한 뒤에야 대기열을 채운다(아니면 경계가 1 어긋난다).
        publisher.onTeamChanged(new SupportTeamChangedEvent(0L));
        assertThat(started.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        for (long id = 1; id <= QUEUE_CAPACITY; id++) {
            publisher.onTeamChanged(new SupportTeamChangedEvent(id));
        }
        assertThat(appender.warns).isEmpty(); // 용량만큼은 모두 받아들였다

        assertThatCode(() -> publisher.onTeamChanged(new SupportTeamChangedEvent(5000L)))
                .doesNotThrowAnyException();

        ILoggingEvent warn = appender.warns.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(warn).isNotNull();
        assertThat(warn.getFormattedMessage()).contains("userAccountId=5000");
        release.countDown();
    }
}
