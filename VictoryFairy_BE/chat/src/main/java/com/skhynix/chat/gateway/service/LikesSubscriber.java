package com.skhynix.chat.gateway.service;

import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.LikeSignal;
import com.skhynix.chat.shared.redis.LikeSignalCodec;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 게이트웨이 파드마다 {@code chat:likes} 를 구독한다(CHAT-LK-24·35~37·39). 받은 신호는 {@link LikesThrottler} 에 넘기기만 한다.
 *
 * <p><b>리스너 컨테이너를 빈으로 두지 않고 여기서 만든다.</b> 컨테이너의 {@code start()} 는 첫 구독이 확인될 때까지
 * 최대 2초 블로킹하고, 그 안에 Redis 에 붙지 못하면 예외를 던진다. 빈으로 두면 Redis 가 죽어 있을 때 컨텍스트 기동이
 * 실패한다 — 메시지(Kafka)·구독까지 함께 죽는다. 그래서 별도 스레드에서 붙을 때까지 재시도한다(1초부터 두 배씩, 상한 30초).
 * 첫 구독 실패는 컨테이너 자체 복구가 돌지 않아(초기 시도는 실패로 끝난다) 이 재시도가 필요하다.
 *
 * <p>구독이 맺어진 뒤의 끊김은 Lettuce 가 재연결·재구독한다(재연결 지연 상한 30초 = Lettuce 기본값). 그동안 Lettuce 가
 * {@code Cannot reconnect to [...]} WARN 을 남기고, 열린 SSE 는 건드리지 않는다(CHAT-LK-35·36). 컨테이너가 끊김을
 * 알아챈 경우(구독 명령 자체가 실패)의 복구도 같은 상한(30초)의 지수 백오프로 맞췄다.
 *
 * <p><b>스레드</b>: 메시지는 Lettuce I/O 스레드에서 그대로 받는다({@link SyncTaskExecutor}). 컨테이너 기본값은 메시지마다
 * 새 스레드를 만드는 {@code SimpleAsyncTaskExecutor} 라 연타 경기에서 스레드가 폭증한다. 여기서는 파싱과 넘기기만 하므로
 * I/O 스레드를 오래 잡지 않는다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class LikesSubscriber implements SmartLifecycle, MessageListener, SubscriptionListener {

    /** Kafka 컨슈머와 같은 단계 — 수신원이 가장 먼저 멈추고, 그다음 스로틀·쓰기 풀·레지스트리 순이다. */
    public static final int PHASE = GatewayConsumer.PHASE;

    private static final long INITIAL_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 30_000L;
    private static final long STOP_JOIN_MS = 3_000L;

    private final RedisConnectionFactory connectionFactory;
    private final LikeSignalCodec codec;
    private final LikesThrottler throttler;

    private final Object lock = new Object();
    // lock 으로 보호
    private boolean running;
    private RedisMessageListenerContainer container;
    private ScheduledExecutorService recoveryExecutor;
    private Thread subscribeThread;

    public LikesSubscriber(RedisConnectionFactory connectionFactory, LikeSignalCodec codec, LikesThrottler throttler) {
        this.connectionFactory = connectionFactory;
        this.codec = codec;
        this.throttler = throttler;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        LikeSignal signal;
        try {
            signal = codec.read(message.getBody());
        } catch (IllegalArgumentException e) {
            // 그 메시지만 버리고 구독은 계속한다. 원문을 data: 로 흘리지 않는다(CHAT-LK-37).
            log.warn("형식에 맞지 않는 chat:likes 메시지를 버렸다: {}", e.getMessage());
            return;
        }
        throttler.offer(signal.gameId(), signal.teamCode());
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        log.info("chat:likes 구독이 맺어졌다");
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            subscribeThread = new Thread(this::subscribeUntilConnected, "chat-likes-subscribe");
            subscribeThread.setDaemon(true);
            subscribeThread.start();
        }
    }

    private void subscribeUntilConnected() {
        long delay = INITIAL_RETRY_MS;
        while (isRunning()) {
            ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chat-likes-recovery");
                thread.setDaemon(true);
                return thread;
            });
            RedisMessageListenerContainer created = newContainer(recovery);
            try {
                created.start();
                synchronized (lock) {
                    if (running) {
                        container = created;
                        recoveryExecutor = recovery;
                        return;
                    }
                }
                // 붙는 사이 stop() 이 불렸다
                shutdown(created, recovery);
                return;
            } catch (RuntimeException e) {
                log.warn("chat:likes 구독 실패, {}ms 뒤 재시도한다 — 그동안 이 파드에 좋아요가 전달되지 않는다: {}",
                        delay, e.toString());
                shutdown(created, recovery);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            delay = Math.min(delay * 2, MAX_RETRY_MS);
        }
    }

    private RedisMessageListenerContainer newContainer(ScheduledExecutorService recovery) {
        RedisMessageListenerContainer created = new RedisMessageListenerContainer();
        created.setConnectionFactory(connectionFactory);
        created.setTaskExecutor(new SyncTaskExecutor());
        // ScheduledExecutorService 여야 복구 대기를 예약으로 한다. 아니면 호출 스레드(Lettuce I/O 일 수 있다)를 재운다.
        created.setSubscriptionExecutor(recovery);
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_RETRY_MS, 2.0);
        backOff.setMaxInterval(MAX_RETRY_MS);
        created.setRecoveryBackoff(backOff);
        created.addMessageListener(this, ChannelTopic.of(LikeSignal.CHANNEL));
        created.afterPropertiesSet();
        return created;
    }

    private static void shutdown(RedisMessageListenerContainer target, ScheduledExecutorService recovery) {
        try {
            target.destroy();
        } catch (Exception e) {
            log.debug("chat:likes 리스너 컨테이너 정리 실패", e);
        }
        recovery.shutdownNow();
    }

    @Override
    public void stop() {
        Thread thread;
        RedisMessageListenerContainer current;
        ScheduledExecutorService recovery;
        synchronized (lock) {
            running = false;
            thread = subscribeThread;
            current = container;
            recovery = recoveryExecutor;
            subscribeThread = null;
            container = null;
            recoveryExecutor = null;
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (current != null) {
            shutdown(current, recovery);
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
