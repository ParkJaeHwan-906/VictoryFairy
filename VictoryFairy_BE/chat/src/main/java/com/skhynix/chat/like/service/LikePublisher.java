package com.skhynix.chat.like.service;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.ThrottledWarnLog;
import com.skhynix.chat.shared.redis.LikeSignal;
import com.skhynix.chat.shared.redis.LikeSignalCodec;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code PUBLISH chat:likes} 를 기다리지 않고 건다(CHAT-LK-41·50·51·22·23). 재시도하지 않는다 — 놓친 응원은 가치가 없다.
 *
 * <p>요청 스레드는 명령을 걸기만 하고 돌아간다. Redis 가 멈추면 명령마다 {@code spring.data.redis.timeout} 까지
 * 미완료로 남으므로, 미완료 수를 {@code chat.likes.publish-queue-capacity} 로 묶고 넘치면 버린다. 동기 PUBLISH 였다면
 * Redis 장애 동안 요청 스레드가 2초씩 묶여 같은 파드의 메시지 전송·구독까지 밀린다.
 *
 * <p>구독은 boundedElastic 에서 한다. 공유 연결이 한 번도 맺어진 적 없으면(기동 때부터 Redis 가 죽어 있으면)
 * 연결 획득이 연결 타임아웃만큼 블로킹으로 기다리는데, 그걸 요청 스레드에서 하지 않기 위해서다.
 *
 * <p>{@code timeout()} 은 안전망이다. Lettuce 명령 타임아웃이 먼저 걸리는 게 정상이지만, 어떤 이유로든 완료 신호가
 * 오지 않으면 미완료 수가 줄지 않아 상한이 영구히 찬 채로 남는다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@Slf4j
public class LikePublisher {

    private static final long WARN_INTERVAL_SECONDS = 10L;

    private final ReactiveStringRedisTemplate redis;
    private final LikeSignalCodec codec;
    private final LikeMetrics metrics;
    private final int capacity;
    private final Duration timeout;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ThrottledWarnLog queueFullLog =
            new ThrottledWarnLog(log, "좋아요 발행 대기열이 가득 차 버렸다", WARN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    private final ThrottledWarnLog failureLog =
            new ThrottledWarnLog(log, "좋아요 PUBLISH 실패, 재시도 없이 버렸다", WARN_INTERVAL_SECONDS, TimeUnit.SECONDS);

    public LikePublisher(ReactiveRedisConnectionFactory connectionFactory, LikeSignalCodec codec, LikeMetrics metrics,
            ChatLikesProperties properties, @Value("${spring.data.redis.timeout:2s}") Duration redisTimeout) {
        this.redis = new ReactiveStringRedisTemplate(connectionFactory);
        this.codec = codec;
        this.metrics = metrics;
        this.capacity = properties.publishQueueCapacity();
        this.timeout = redisTimeout;
    }

    public void publish(String gameId, String teamCode) {
        if (inFlight.incrementAndGet() > capacity) {
            inFlight.decrementAndGet();
            metrics.queueFull();
            queueFullLog.record(null);
            return;
        }
        Mono<Long> command;
        try {
            String json = codec.write(new LikeSignal(gameId, teamCode));
            command = redis.convertAndSend(LikeSignal.CHANNEL, json);
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            fail(e);
            return;
        }
        command.subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                .doFinally(signal -> inFlight.decrementAndGet())
                .subscribe(receivers -> { }, this::fail);
    }

    private void fail(Throwable cause) {
        metrics.publishFailed();
        failureLog.record(cause);
    }
}
