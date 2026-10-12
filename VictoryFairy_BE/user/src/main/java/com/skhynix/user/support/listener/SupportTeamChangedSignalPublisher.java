package com.skhynix.user.support.listener;

import com.skhynix.user.support.event.SupportTeamChangedEvent;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 구단 변경이 커밋된 뒤 {@code support:team-changed} 로 계정 id 를 발행해 chat 의 응원 구단 캐시를 지우게 한다.
 * 채널·페이로드는 chat 과의 앱 간 계약이다(정본: {@code docs/requirements/chat/game-chat-likes.md}).
 *
 * <p>{@code RedisGameEventPublisher} 처럼 {@code @Profile("prod")} 를 붙이면 안 된다. 그쪽이 prod 전용인 이유는 구독
 * 컨테이너가 기동 시 Redis 연결을 요구해서인데, 발행만 하는 이 클래스는 해당하지 않고 dev 에서도 신호가 가야 한다.
 *
 * <p>요청 스레드에서 발행하지 않는다. 이 앱은 Redis 명령 타임아웃을 설정하지 않아 Lettuce 기본 60초가 걸리고,
 * Redis 가 매달리면 그만큼 구단 변경 응답이 늘어난다. 타임아웃을 줄이는 대신 비동기로 둔 이유는 그 설정이
 * 이메일 인증·OAuth 티켓 경로에도 함께 걸리기 때문이다. 전용 단일 스레드에 상한 있는 대기열을 두어,
 * Redis 장애가 길어져도 쌓이는 작업이 메모리를 잡아먹지 않게 했다. 넘친 신호는 버린다 — 놓쳐도 chat TTL(60초)이
 * 지연의 상한을 정하므로 재시도하지 않는다.
 */
@Component
@Slf4j
public class SupportTeamChangedSignalPublisher {

    static final String CHANNEL = "support:team-changed";

    // 구단 변경은 사람이 누르는 드문 쓰기라 정상 시 대기열은 비어 있다. Redis 가 60초씩 매달릴 때만 찬다.
    private static final int QUEUE_CAPACITY = 1000;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor executor;

    public SupportTeamChangedSignalPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "support-team-changed-publisher");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    // AFTER_COMMIT 이라 롤백된 요청에서는 호출되지 않고, 이 시점엔 커밋으로 계정 행 락도 풀려 있다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamChanged(SupportTeamChangedEvent event) {
        Long userAccountId = event.userAccountId();
        try {
            executor.execute(() -> publish(userAccountId));
        } catch (RejectedExecutionException e) {
            log.warn("응원 구단 변경 신호 발행 대기열이 가득 차 버렸다 userAccountId={}", userAccountId);
        }
    }

    private void publish(Long userAccountId) {
        try {
            String payload = objectMapper.writeValueAsString(new Payload(userAccountId));
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("응원 구단 변경 신호 발행 실패, 재시도하지 않는다 userAccountId={}", userAccountId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    record Payload(Long userAccountId) {
    }
}
