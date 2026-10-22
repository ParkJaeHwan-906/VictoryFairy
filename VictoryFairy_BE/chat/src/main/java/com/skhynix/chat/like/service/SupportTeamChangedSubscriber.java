package com.skhynix.chat.like.service;

import com.skhynix.chat.shared.ChatRoles;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * user 앱이 응원 구단 변경을 커밋한 뒤 보내는 {@code support:team-changed} 를 구독해 {@link SupportTeamCodeCache} 항목을
 * 지운다.
 *
 * <p><b>역할 조건이 캐시와 같은 API 역할이다.</b> 게이트웨이용 {@code LikesSubscriber} 에 얹지 않은 이유: 게이트웨이 전용
 * 파드가 생기면 캐시 없는 파드가 신호를 받고, 정작 API 파드의 캐시는 아무도 지우지 않게 된다.
 *
 * <p><b>리스너 컨테이너를 빈으로 두지 않는 이유는 {@code LikesSubscriber} 와 같다</b> — 컨테이너 {@code start()} 가 첫 구독
 * 실패 시 예외를 던져 Redis 가 죽어 있으면 기동이 실패하고, 첫 시도에는 자체 복구가 없다. 그래서 별도 스레드에서
 * 1초→30초 백오프로 붙을 때까지 재시도한다. 맺어진 뒤의 끊김은 Lettuce 가 재연결·재구독한다(그동안 Lettuce WARN).
 *
 * <p><b>구독이 맺어질 때마다 캐시를 전부 비운다</b>({@link #onChannelSubscribed}). 끊긴 동안 놓친 신호를 알 수 없으니 그
 * 이전 캐시를 믿지 않는다. Lettuce 재구독·컨테이너 복구·최초 구독 모두 이 콜백을 거친다. 최초 구독이 늦어진 경우에도
 * 그 사이 쌓인 항목은 신호 없이 채워진 것이라 비우는 게 맞다.
 *
 * <p><b>스레드</b>: 메시지·구독 콜백은 Lettuce I/O 스레드에서 그대로 받는다({@link SyncTaskExecutor}) — 하는 일이 맵 연산뿐이다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@Slf4j
public class SupportTeamChangedSubscriber implements SmartLifecycle, MessageListener, SubscriptionListener {

    /** user 앱과의 계약. 새 구단 코드는 싣지 않는다 — 값의 출처는 DB 하나여야 연속 변경의 신호 순서와 무관해진다. */
    public static final String CHANNEL = "support:team-changed";

    /** 다른 구독자({@code LikesSubscriber})와 같은 단계. 웹 서버 graceful shutdown 보다만 높으면 된다. */
    public static final int PHASE = Integer.MAX_VALUE - 10;

    private static final long INITIAL_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 30_000L;
    private static final long STOP_JOIN_MS = 3_000L;

    private final RedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;
    private final SupportTeamCodeCache cache;

    private final Object lock = new Object();
    // lock 으로 보호
    private boolean running;
    private RedisMessageListenerContainer container;
    private ScheduledExecutorService recoveryExecutor;
    private Thread subscribeThread;

    public SupportTeamChangedSubscriber(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper, SupportTeamCodeCache cache) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long userAccountId;
        try {
            userAccountId = parseUserAccountId(message.getBody());
        } catch (IllegalArgumentException e) {
            // 그 메시지만 버린다. 캐시 전체를 비우지 않는다 — 깨진 발행 하나가 모든 사용자의 SELECT 를 부르면 안 된다.
            log.warn("형식에 맞지 않는 {} 메시지를 버렸다: {}", CHANNEL, e.getMessage());
            return;
        }
        cache.invalidate(userAccountId);
    }

    /**
     * 레코드 바인딩 대신 트리로 읽는다 — 바인딩은 문자열 {@code "123"}·실수 {@code 1.0} 을 숫자로 바꿔 받아 형식 위반을
     * 통과시킨다.
     *
     * @throws IllegalArgumentException JSON 객체가 아니거나 {@code userAccountId} 가 양의 정수(JSON 숫자)가 아니면
     */
    long parseUserAccountId(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 이 아니다", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("JSON 객체가 아니다");
        }
        JsonNode id = root.get("userAccountId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new IllegalArgumentException("userAccountId 가 양의 정수가 아니다");
        }
        return id.longValue();
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        cache.invalidateAll();
        log.info("{} 구독이 맺어졌다 — 응원 구단 캐시를 비웠다", CHANNEL);
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            subscribeThread = new Thread(this::subscribeUntilConnected, "support-team-changed-subscribe");
            subscribeThread.setDaemon(true);
            subscribeThread.start();
        }
    }

    private void subscribeUntilConnected() {
        long delay = INITIAL_RETRY_MS;
        while (isRunning()) {
            ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "support-team-changed-recovery");
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
                log.warn("{} 구독 실패, {}ms 뒤 재시도한다 — 그동안 구단 변경은 캐시 TTL 만큼 늦게 반영된다: {}",
                        CHANNEL, delay, e.toString());
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
        created.addMessageListener(this, ChannelTopic.of(CHANNEL));
        created.afterPropertiesSet();
        return created;
    }

    private static void shutdown(RedisMessageListenerContainer target, ScheduledExecutorService recovery) {
        try {
            target.destroy();
        } catch (Exception e) {
            log.debug("{} 리스너 컨테이너 정리 실패", CHANNEL, e);
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
