package com.skhynix.chat.gateway.service;

import com.skhynix.chat.global.config.ChatPresenceProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.realtime.SubscriptionPresenceListener;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.ThrottledWarnLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * 방 접속 인원 집계와 SSE {@code presence} 전달(CHAT-PR-1~30).
 *
 * <ul>
 *   <li>구독 성립: 소유자를 자기로 덮어쓰며 등록하고, 그 구독자에게 현재 인원 1건(CHAT-PR-5·11).</li>
 *   <li>하트비트(15초): 로컬 구독 계정의 생존 시각 갱신. 소유자는 없을 때만 채운다(CHAT-PR-6·10).</li>
 *   <li>{@code broadcast-interval-ms} 주기: 로컬 구독자가 있는 방마다 만료를 걷고 세서, 구독자에게 마지막으로 보낸 값과
 *       다르면 보낸다(CHAT-PR-7·12~14·18).</li>
 *   <li>구독 종료: 이 파드에 그 계정의 그 방 구독이 더 없을 때만, 소유자가 자기인 기록을 원자적으로 지운다(CHAT-PR-8·9).</li>
 * </ul>
 *
 * <p><b>스레드 모델</b>: Redis 왕복은 전부 전용 단일 스레드({@code chat-presence})에서만 한다. 하트비트 스케줄러 스레드는
 * 방 수명 작업과 공유돼 Redis 타임아웃만큼 붙잡히면 {@code :ping} 이 밀린다(CHAT-PR-24). 이 스레드도 소켓에 쓰지 않는다 —
 * {@link SseFrameWriter#deliver} 로 구독자 대기열에 넣을 뿐이다(CHAT-PR-17).
 *
 * <p><b>왜 한 스레드인가</b>: 종료 판정의 "로컬 구독이 더 없는가" 확인과 그 뒤의 삭제 스크립트 사이에 같은 파드의 새 등록
 * 스크립트가 끼면, 방금 등록한 member 를 지우게 된다. 등록·삭제를 한 스레드에 줄 세우면 확인과 삭제가 붙어서 실행된다.
 * 새 구독은 맵에 먼저 들어간 뒤 등록을 맡기므로, 같은 파드 재구독의 옛 구독 종료는 맵에서 새 구독을 보고 건너뛴다.
 * 다른 파드 쪽 순서(새 소유자 기록 → 옛 구독 종료 판정)는 {@link #joined} 가 등록 반영을 기다린 뒤 축출 명령이 나가서 지켜진다.
 *
 * <p><b>수명</b>: SmartLifecycle 이 아니다. 레지스트리의 start()/stop() 이 {@link #registryStarted}·{@link #registryStopped}
 * 로 끌고 간다 — 이유는 {@link SubscriptionPresenceListener#registryStopped} 참고. 그래서 정지는 늘 "레지스트리가 전 구독을
 * 닫음 → 그 퇴장 반영 → 실행기 내림" 순서이고, 레지스트리와 같은 시점(쓰기 풀보다 먼저, Redis 연결 팩토리보다 먼저)에 끝난다.
 *
 * <p>Redis 실패는 그 주기만 건너뛴다. 대체 값을 보내지 않고 스트림도 닫지 않는다(CHAT-PR-23·24). WARN 은 10초 1줄(CHAT-PR-27).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class PresenceTracker implements SubscriptionPresenceListener {

    /** 아직 처리하지 않은 등록·퇴장 상한. Redis 장애로 이 스레드가 막힌 동안 힙을 채우지 않게 하는 안전망이다. */
    static final int MAX_PENDING = 100_000;

    private static final long SHUTDOWN_AWAIT_MS = 3_000L;
    private static final long WARN_INTERVAL_SECONDS = 10L;

    private final SseEmitterRegistry registry;
    private final SseFrameWriter writer;
    private final PresenceStore store;
    private final long intervalMs;
    private final long joinWaitMs;

    private final ConcurrentLinkedQueue<Join> joins = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChatSubscription> leaves = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    // 같은 작업이 대기열에 여러 개 쌓이지 않게 한다. 장애 중 하트비트·종료가 몰려도 한 번에 묶어 처리한다.
    private final AtomicBoolean joinDrainQueued = new AtomicBoolean();
    private final AtomicBoolean leaveDrainQueued = new AtomicBoolean();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();

    private final ThrottledWarnLog redisFailureLog = new ThrottledWarnLog(log,
            "접속 인원 Redis 명령 실패, 이번 주기의 presence 를 건너뛴다", WARN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    private final ThrottledWarnLog overflowLog = new ThrottledWarnLog(log,
            "접속 인원 처리 대기열이 가득 차 등록·퇴장을 버렸다(생존 갱신·만료가 보정한다)", WARN_INTERVAL_SECONDS, TimeUnit.SECONDS);

    // stop() 뒤 start() 로 다시 살 수 있어야 한다(SseFrameWriter 와 같은 이유). 생성자에서도 만든다 — 웹 서버가 이 빈의
    // start() 보다 먼저 떠서 그 사이 들어온 구독도 등록할 수 있어야 한다.
    private volatile ScheduledThreadPoolExecutor executor;
    private volatile boolean running;

    public PresenceTracker(SseEmitterRegistry registry, SseFrameWriter writer, PresenceStore store,
            ChatPresenceProperties properties, @Value("${spring.data.redis.timeout:2s}") Duration redisTimeout) {
        this.registry = registry;
        this.writer = writer;
        this.store = store;
        this.intervalMs = Math.max(1L, properties.broadcastIntervalMs());
        this.joinWaitMs = Math.max(1L, redisTimeout.toMillis());
        this.executor = newExecutor();
        registry.setPresenceListener(this);
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "chat-presence");
            thread.setDaemon(true);
            return thread;
        });
        created.setRemoveOnCancelPolicy(true);
        // 정지 때 주기 집계는 버리고, 이미 맡은 퇴장 반영은 흘린다.
        created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        created.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return created;
    }

    // ---- 레지스트리 콜백 ----

    /**
     * 요청 스레드. 등록 반영을 최대 Redis 명령 타임아웃만큼 기다린다. 넘기면 기다리지 않고 진행한다 — 등록은 뒤에서 계속되고,
     * 구독은 200 으로 유지된다(CHAT-PR-25).
     */
    @Override
    public void joined(ChatSubscription subscription) {
        if (!reserve()) {
            // 등록을 못 맡겼다. 다음 생존 갱신이 반영하고 다음 주기가 값을 보낸다.
            subscription.presenceSent(ChatSubscription.PRESENCE_UNKNOWN);
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        joins.add(new Join(subscription, done));
        if (!schedule(joinDrainQueued, this::drainJoins)) {
            subscription.presenceSent(ChatSubscription.PRESENCE_UNKNOWN);
            return;
        }
        try {
            done.get(joinWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            log.debug("접속 인원 등록 반영을 기다리지 않고 진행 gameId={}", subscription.gameId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void left(ChatSubscription subscription) {
        if (!reserve()) {
            return; // 만료 창(CHAT-PR-7)이 걷는다
        }
        leaves.add(subscription);
        schedule(leaveDrainQueued, this::drainLeaves);
    }

    /** 하트비트 스케줄러 스레드. 생존 갱신을 맡기기만 한다(CHAT-PR-24). */
    @Override
    public void heartbeat() {
        schedule(refreshQueued, this::refresh);
    }

    private boolean reserve() {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            overflowLog.record(null);
            return false;
        }
        return true;
    }

    /** @return 작업이 대기 중이면 true(이번에 맡겼든 이미 있었든). 실행기가 내려가 맡기지 못했으면 false */
    private boolean schedule(AtomicBoolean queued, Runnable task) {
        if (!queued.compareAndSet(false, true)) {
            return true;
        }
        try {
            executor.execute(() -> {
                queued.set(false);
                safely(task);
            });
            return true;
        } catch (RejectedExecutionException e) {
            // 종료 중. 남은 항목은 stop() 이 비운다.
            queued.set(false);
            return false;
        }
    }

    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 예외가 새면 주기 작업이 조용히 멈춘다.
            log.error("접속 인원 처리 실패", e);
        }
    }

    // ---- presence 스레드 ----

    private void drainJoins() {
        List<Join> batch = new ArrayList<>();
        Join join;
        while ((join = joins.poll()) != null) {
            batch.add(join);
        }
        pending.addAndGet(-batch.size());
        if (batch.isEmpty()) {
            return;
        }
        Map<String, List<Join>> byRoom = new LinkedHashMap<>();
        for (Join item : batch) {
            byRoom.computeIfAbsent(item.subscription().gameId(), key -> new ArrayList<>()).add(item);
        }
        boolean failed = false;
        for (Map.Entry<String, List<Join>> room : byRoom.entrySet()) {
            String gameId = room.getKey();
            Set<ChatSubscription> local = registry.subscriptions(gameId);
            // 등록 전에 이미 닫힌 구독은 등록하지 않는다 — 퇴장이 먼저 처리됐으면 지워진 member 를 되살리게 된다.
            List<Join> open = room.getValue().stream().filter(item -> local.contains(item.subscription())).toList();
            try {
                if (open.isEmpty() || failed) {
                    continue;
                }
                long count = store.join(gameId, distinctUsers(open.stream().map(Join::subscription).toList()),
                        registry.instanceId());
                // 로컬 하한보다 작으면 초기 값도 보내지 않는다(CHAT-PR-31). 이 구독들은 finally 에서 "값 못 받음"이 되고
                // 다음 유효 주기에 받는다(CHAT-PR-32).
                if (count < localAccounts(local, open)) {
                    continue;
                }
                SseFrame frame = SseFrame.presence(count);
                for (Join item : open) {
                    item.subscription().presenceSent(count);
                    writer.deliver(item.subscription(), frame);
                }
            } catch (RuntimeException e) {
                failed = true; // 같은 장애로 남은 방도 타임아웃까지 기다리지 않게 이번 묶음은 접는다
                redisFailureLog.record(e);
            } finally {
                for (Join item : room.getValue()) {
                    // 등록이 안 된 구독도 주기 전달 대상에 넣는다. 반영은 다음 생존 갱신이 한다(CHAT-PR-25·10).
                    if (item.subscription().presenceSent() == ChatSubscription.PRESENCE_NOT_JOINED) {
                        item.subscription().presenceSent(ChatSubscription.PRESENCE_UNKNOWN);
                    }
                    item.done().complete(null);
                }
            }
        }
    }

    private void drainLeaves() {
        List<ChatSubscription> batch = new ArrayList<>();
        ChatSubscription subscription;
        while ((subscription = leaves.poll()) != null) {
            batch.add(subscription);
        }
        pending.addAndGet(-batch.size());
        Map<String, Set<Long>> byRoom = new LinkedHashMap<>();
        for (ChatSubscription item : batch) {
            byRoom.computeIfAbsent(item.gameId(), key -> new LinkedHashSet<>()).add(item.userAccountId());
        }
        for (Map.Entry<String, Set<Long>> room : byRoom.entrySet()) {
            String gameId = room.getKey();
            // 이 파드에 그 계정의 그 방 구독이 아직 있으면 빼지 않는다(CHAT-PR-9 (b) — 같은 파드 재구독).
            Set<Long> stillHere = new LinkedHashSet<>(distinctUsers(registry.subscriptions(gameId)));
            List<Long> gone = room.getValue().stream().filter(id -> !stillHere.contains(id)).toList();
            if (gone.isEmpty()) {
                continue;
            }
            try {
                store.leave(gameId, gone, registry.instanceId());
            } catch (RuntimeException e) {
                redisFailureLog.record(e);
                return; // 남은 방은 만료 창이 걷는다
            }
        }
    }

    /** 생존 갱신(CHAT-PR-6·10·26). */
    private void refresh() {
        for (String gameId : List.copyOf(registry.roomIds())) {
            List<Long> users = distinctUsers(registry.subscriptions(gameId));
            if (users.isEmpty()) {
                continue;
            }
            try {
                store.refresh(gameId, users, registry.instanceId());
            } catch (RuntimeException e) {
                redisFailureLog.record(e);
                return;
            }
        }
    }

    /** 주기 집계·전달(CHAT-PR-7·12~14·18·22). */
    void tick() {
        for (String gameId : List.copyOf(registry.roomIds())) {
            Set<ChatSubscription> subscriptions = registry.subscriptions(gameId);
            if (subscriptions.isEmpty()) {
                continue;
            }
            long count;
            try {
                count = store.count(gameId);
            } catch (RuntimeException e) {
                // 보내지 않는다. 화면에는 마지막 값이 남는다(CHAT-PR-23).
                redisFailureLog.record(e);
                return;
            }
            // 이 파드에 지금 있는 계정보다 작은 집계는 명백히 틀렸다(키 소실 직후 등). 보내지 않고 마지막 값을 둔다.
            // 로컬 값으로 대신 보내지도 않는다(CHAT-PR-31·23).
            if (count < localAccounts(subscriptions, List.of())) {
                continue;
            }
            SseFrame frame = null;
            for (ChatSubscription subscription : subscriptions) {
                long sent = subscription.presenceSent();
                // 등록 전 구독은 건너뛴다 — 첫 값은 등록이 :connected 뒤에 보낸다(CHAT-PR-11).
                // 값을 한 번도 못 받은 구독(PRESENCE_UNKNOWN)은 어떤 count 와도 달라 파드의 마지막 값과 같아도 받는다(CHAT-PR-32).
                // 이미 받은 구독은 같은 값이면 보내지 않는다(CHAT-PR-14).
                if (sent == ChatSubscription.PRESENCE_NOT_JOINED || sent == count) {
                    continue;
                }
                if (frame == null) {
                    frame = SseFrame.presence(count);
                }
                subscription.presenceSent(count);
                writer.deliver(subscription, frame);
            }
        }
    }

    /**
     * CHAT-PR-31 의 하한: 이 파드에서 이 방을 구독 중이고 Redis 등록을 거친 고유 계정 수. 레지스트리 맵만 순회한다(Redis 왕복 없음).
     *
     * <p>등록 전({@code PRESENCE_NOT_JOINED}) 구독은 뺀다 — 아직 Redis 에 반영되지 않은 계정이라, 집계가 그만큼 작아도 틀린
     * 값이 아니다. 넣으면 입장이 몰리는 동안 등록 대기가 남은 방의 주기 전달이 통째로 멈춘다.
     *
     * @param joining 이번에 막 등록한 구독(아직 NOT_JOINED 표시라 따로 센다)
     */
    private static long localAccounts(Set<ChatSubscription> subscriptions, List<Join> joining) {
        Set<Long> users = new HashSet<>();
        for (ChatSubscription subscription : subscriptions) {
            if (subscription.presenceSent() != ChatSubscription.PRESENCE_NOT_JOINED) {
                users.add(subscription.userAccountId());
            }
        }
        for (Join item : joining) {
            users.add(item.subscription().userAccountId());
        }
        return users.size();
    }

    private static List<Long> distinctUsers(Iterable<ChatSubscription> subscriptions) {
        Set<Long> users = new LinkedHashSet<>();
        for (ChatSubscription subscription : subscriptions) {
            users.add(subscription.userAccountId());
        }
        return new ArrayList<>(users);
    }

    // ---- 수명 ----

    @Override
    public void registryStarted() {
        start();
    }

    @Override
    public void registryStopped() {
        stop();
    }

    /** 주기 집계를 시작한다. 이미 돌고 있으면 아무것도 하지 않는다. stop() 뒤 다시 부를 수 있다. */
    public synchronized void start() {
        if (running) {
            return;
        }
        if (executor.isShutdown()) {
            executor = newExecutor();
        }
        executor.scheduleWithFixedDelay(() -> safely(this::tick), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** 이미 맡은 퇴장을 반영하고(제한 시간 안에서) 내린다. 여러 번 불러도 된다. */
    public synchronized void stop() {
        running = false;
        ScheduledThreadPoolExecutor current = executor;
        try {
            current.execute(() -> safely(this::drainLeaves));
        } catch (RejectedExecutionException ignored) {
            // 이미 내려갔다
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(SHUTDOWN_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 실행기가 끝난 뒤라 비워도 된다. 남은 퇴장은 만료 창이 걷는다. 기다리는 요청 스레드는 풀어 준다.
        Join join;
        while ((join = joins.poll()) != null) {
            join.done().complete(null);
        }
        leaves.clear();
        pending.set(0);
        joinDrainQueued.set(false);
        leaveDrainQueued.set(false);
        refreshQueued.set(false);
    }

    public boolean isRunning() {
        return running;
    }

    private record Join(ChatSubscription subscription, CompletableFuture<Void> done) {
    }
}
