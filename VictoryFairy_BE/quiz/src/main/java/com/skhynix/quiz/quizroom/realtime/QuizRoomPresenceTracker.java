package com.skhynix.quiz.quizroom.realtime;

import com.skhynix.quiz.quizroom.dto.PresencePayload;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry.Subscription;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 퀴즈방 풀이 중 인원 집계와 SSE {@code presence} 전달.
 *
 * <ul>
 *   <li>구독 성립: 소유자를 자기로 덮어쓰며 등록하고, 그 구독자에게 현재 인원 1건.</li>
 *   <li>하트비트(15초): 로컬 구독 계정의 생존 시각 갱신 — 만료(기본 45초)보다 짧아야 산 연결이 빠지지 않는다.</li>
 *   <li>{@code broadcast-interval-ms}(기본 2초) 주기: 로컬 구독자가 있는 방마다 만료를 걷고 세서, 구독자에게 마지막으로
 *       보낸 값과 다르면 보낸다.</li>
 *   <li>구독 종료: 이 파드에 그 계정의 그 방 구독이 더 없을 때만 소유자가 자기인 기록을 지운다.</li>
 * </ul>
 *
 * <p><b>Redis 왕복은 전부 전용 단일 스레드에서만 한다.</b> "로컬 구독이 더 없는가" 확인과 삭제 사이에 같은 파드의 새 등록이
 * 끼면 방금 등록한 계정을 지우게 되는데, 등록·삭제를 한 스레드에 줄 세우면 그 틈이 없다. 하트비트 스케줄러 스레드도
 * Redis 타임아웃만큼 붙잡히지 않는다.
 *
 * <p>Redis 실패는 그 주기만 건너뛴다(대체 값을 보내지 않고 스트림도 닫지 않는다). WARN 은 10초에 1줄.
 */
@Component
@Slf4j
public class QuizRoomPresenceTracker implements QuizRoomSseRegistry.PresenceListener {

    public static final String EVENT_NAME = "presence";

    private static final long WARN_INTERVAL_MS = 10_000L;

    private final QuizRoomSseRegistry registry;
    private final QuizRoomPresenceStore store;
    private final long joinWaitMs;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicLong lastWarnAt = new AtomicLong();

    public QuizRoomPresenceTracker(QuizRoomSseRegistry registry, QuizRoomPresenceStore store,
            @Value("${quiz.room.presence.broadcast-interval-ms:2000}") long intervalMs,
            @Value("${spring.data.redis.timeout:2s}") Duration redisTimeout) {
        this.registry = registry;
        this.store = store;
        this.joinWaitMs = Math.max(1L, redisTimeout.toMillis());
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "quiz-room-presence");
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        long interval = Math.max(1L, intervalMs);
        executor.scheduleWithFixedDelay(() -> safely(this::tick), interval, interval, TimeUnit.MILLISECONDS);
        registry.setPresenceListener(this);
    }

    /**
     * 요청 스레드. 등록과 첫 {@code presence} 전송을 presence 스레드에서 끝낼 때까지 최대 Redis 타임아웃만큼 기다린다 —
     * 호출부가 그 뒤에 다른 파드 축출 명령을 내야 옛 구독의 종료 판정보다 새 소유자 기록이 앞선다. 넘기면 기다리지 않는다.
     */
    public void joined(Subscription subscription) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    join(subscription);
                } finally {
                    done.complete(null);
                }
            });
        } catch (RejectedExecutionException e) {
            subscription.presenceSent(QuizRoomSseRegistry.PRESENCE_UNKNOWN);
            return;
        }
        try {
            done.get(joinWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            log.debug("퀴즈방 인원 등록 반영을 기다리지 않고 진행 roomId={}", subscription.roomId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void left(Subscription subscription) {
        submit(() -> leave(subscription));
    }

    @Override
    public void heartbeat() {
        submit(this::refresh);
    }

    // ---- presence 스레드 ----

    private void join(Subscription subscription) {
        try {
            // 등록 전에 이미 닫힌 구독은 등록하지 않는다 — 퇴장이 먼저 처리됐으면 지워진 기록을 되살리게 된다
            if (!registry.subscriptions(subscription.roomId()).contains(subscription)) {
                return;
            }
            long count = store.join(subscription.roomId(), List.of(subscription.userAccountId()),
                    registry.instanceId());
            subscription.presenceSent(count);
            registry.send(subscription, EVENT_NAME, new PresencePayload(count));
        } catch (RuntimeException e) {
            warn(e);
        } finally {
            if (subscription.presenceSent() == QuizRoomSseRegistry.PRESENCE_NOT_JOINED) {
                // 등록 못 한 구독도 주기 전달 대상에 넣는다. Redis 반영은 다음 생존 갱신이 한다
                subscription.presenceSent(QuizRoomSseRegistry.PRESENCE_UNKNOWN);
            }
        }
    }

    private void leave(Subscription subscription) {
        // 같은 파드에서 같은 방을 다시 구독 중이면 빼지 않는다(새 구독이 먼저 맵에 들어간 뒤 옛 구독이 닫힌다)
        boolean stillHere = registry.subscriptions(subscription.roomId()).stream()
                .anyMatch(other -> other.userAccountId().equals(subscription.userAccountId()));
        if (stillHere) {
            return;
        }
        try {
            store.leave(subscription.roomId(), List.of(subscription.userAccountId()), registry.instanceId());
        } catch (RuntimeException e) {
            warn(e); // 만료 창이 걷는다
        }
    }

    private void refresh() {
        for (Long roomId : List.copyOf(registry.roomIds())) {
            List<Long> users = distinctUsers(registry.subscriptions(roomId));
            if (users.isEmpty()) {
                continue;
            }
            try {
                store.refresh(roomId, users, registry.instanceId());
            } catch (RuntimeException e) {
                warn(e);
                return;
            }
        }
    }

    void tick() {
        for (Long roomId : List.copyOf(registry.roomIds())) {
            Set<Subscription> subscriptions = registry.subscriptions(roomId);
            if (subscriptions.isEmpty()) {
                continue;
            }
            long count;
            try {
                count = store.count(roomId);
            } catch (RuntimeException e) {
                warn(e); // 보내지 않는다 — 화면에는 마지막 값이 남는다
                return;
            }
            PresencePayload payload = new PresencePayload(count);
            for (Subscription subscription : subscriptions) {
                long sent = subscription.presenceSent();
                // 등록 전 구독은 건너뛴다(첫 값은 등록이 보낸다). 같은 값이면 프레임을 내지 않는다
                if (sent == QuizRoomSseRegistry.PRESENCE_NOT_JOINED || sent == count) {
                    continue;
                }
                subscription.presenceSent(count);
                registry.send(subscription, EVENT_NAME, payload);
            }
        }
    }

    private static List<Long> distinctUsers(Set<Subscription> subscriptions) {
        Set<Long> users = new LinkedHashSet<>();
        for (Subscription subscription : subscriptions) {
            users.add(subscription.userAccountId());
        }
        return new ArrayList<>(users);
    }

    private void submit(Runnable task) {
        try {
            executor.execute(() -> safely(task));
        } catch (RejectedExecutionException ignored) {
            // 종료 중 — 남은 기록은 만료 창이 걷는다
        }
    }

    private void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 예외가 새면 주기 작업이 조용히 멈춘다
            log.error("퀴즈방 인원 처리 실패", e);
        }
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("퀴즈방 인원 Redis 명령 실패, 이번 주기의 presence 를 건너뛴다", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
