package com.skhynix.chat.gateway.service;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.ThrottledWarnLog;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 방별 leading-edge 스로틀(CHAT-LK-42~47·27·30·32).
 *
 * <ul>
 *   <li>조용한 방(창 없음)에 좋아요가 오면 그 구단 코드 하나를 즉시 보내고 {@code throttle-window-ms} 창을 연다.</li>
 *   <li>창이 열린 동안 온 코드는 중복 없이 모은다.</li>
 *   <li>창이 끝날 때 모음이 있으면 한 프레임으로 보내고 새 창을 연다. 비었으면 조용한 상태로 돌아간다.</li>
 *   <li>그 파드에 그 방 구독자가 없으면 버리고 창도 열지 않는다.</li>
 * </ul>
 *
 * <p><b>스레드 모델</b>: 방 상태는 전용 스레드({@code chat-likes-throttler}) 하나만 만진다. Redis 구독 스레드는
 * {@link #offer} 로 작업을 넘기기만 하고, 창 끝 처리도 같은 스레드에 예약된다. 그래서 방 상태에 잠금이 없다.
 * 이 스레드도 소켓에 쓰지 않는다 — {@link SseFrameWriter#deliver} 로 구독자 대기열에 넣을 뿐이다(CHAT-LK-34).
 * 발신자를 빼지 않고(CHAT-LK-30) 차단 필터도 없다(CHAT-LK-31).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class LikesThrottler implements SmartLifecycle {

    /** 배처와 같은 단계. 구독(먼저 멈춤)보다 늦고 쓰기 풀(나중에 멈춤)보다 빠르다. */
    public static final int PHASE = RoomBatcher.PHASE;

    /**
     * 아직 처리하지 않은 수신 좋아요 상한. 정상이면 거의 0 이다. 이 스레드가 막히는 이상 상황에서 Redis 구독 스레드가
     * 계속 쌓아 힙을 채우지 않게 하는 안전망이다 — 넘치면 버린다(좋아요는 잃어도 되는 신호다).
     */
    static final int MAX_PENDING = 10_000;

    private static final long SHUTDOWN_AWAIT_MS = 1_000L;

    private final SseEmitterRegistry registry;
    private final SseFrameWriter writer;
    private final long windowMs;
    private final AtomicInteger pending = new AtomicInteger();
    private final ThrottledWarnLog overflowLog =
            new ThrottledWarnLog(log, "좋아요 전달 대기열이 가득 차 버렸다", 10L, TimeUnit.SECONDS);

    // 방 → 열린 창의 모음. 이 스레드에서만 읽고 쓴다(stop 의 정리는 실행기 종료 뒤).
    private final Map<String, Set<String>> windows = new HashMap<>();
    // stop() 뒤 start() 로 다시 살 수 있어야 한다(SseFrameWriter 와 같은 이유).
    private volatile ScheduledThreadPoolExecutor executor;
    private volatile boolean running;

    public LikesThrottler(SseEmitterRegistry registry, SseFrameWriter writer, ChatLikesProperties properties) {
        this.registry = registry;
        this.writer = writer;
        this.windowMs = Math.max(1L, properties.throttleWindowMs());
        this.executor = newExecutor();
    }

    private static ScheduledThreadPoolExecutor newExecutor() {
        ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "chat-likes-throttler");
            thread.setDaemon(true);
            return thread;
        });
        created.setRemoveOnCancelPolicy(true);
        created.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return created;
    }

    /** Redis 구독 스레드가 부른다. 넘기기만 하고 바로 돌아온다. */
    public void offer(String gameId, String teamCode) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            overflowLog.record(null);
            return;
        }
        try {
            executor.execute(() -> {
                pending.decrementAndGet();
                receive(gameId, teamCode);
            });
        } catch (RejectedExecutionException e) {
            // 종료 중. 좋아요는 버린다.
            pending.decrementAndGet();
        }
    }

    private void receive(String gameId, String teamCode) {
        Set<String> window = windows.get(gameId);
        if (window != null) {
            window.add(teamCode);
            return;
        }
        Set<ChatSubscription> subscriptions = registry.subscriptions(gameId);
        if (subscriptions.isEmpty()) {
            return;
        }
        // 전송보다 창을 먼저 연다 — 전송이 예외로 끝나도 창 끝 예약이 남아 방이 "창 열림"에 갇히지 않는다.
        openWindow(gameId);
        send(gameId, subscriptions, List.of(teamCode));
    }

    private void closeWindow(String gameId) {
        Set<String> window = windows.remove(gameId);
        if (window == null || window.isEmpty()) {
            return;
        }
        Set<ChatSubscription> subscriptions = registry.subscriptions(gameId);
        if (subscriptions.isEmpty()) {
            return;
        }
        openWindow(gameId);
        send(gameId, subscriptions, window);
    }

    private void openWindow(String gameId) {
        windows.put(gameId, new LinkedHashSet<>());
        try {
            executor.schedule(() -> closeWindow(gameId), windowMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            windows.remove(gameId);
        }
    }

    private void send(String gameId, Set<ChatSubscription> subscriptions, Collection<String> teamCodes) {
        try {
            SseFrame frame = SseFrame.likes(teamCodes);
            for (ChatSubscription subscription : subscriptions) {
                writer.deliver(subscription, frame);
            }
        } catch (RuntimeException e) {
            log.error("좋아요 전달 실패 gameId={}", gameId, e);
        }
    }

    @Override
    public void start() {
        if (executor.isShutdown()) {
            executor = newExecutor();
        }
        running = true;
    }

    /** 열린 창의 모음은 보내지 않고 버린다 — 곧 레지스트리가 전 구독을 닫는다. */
    @Override
    public void stop() {
        running = false;
        ScheduledThreadPoolExecutor current = executor;
        current.shutdownNow();
        try {
            current.awaitTermination(SHUTDOWN_AWAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 실행기가 끝난 뒤라 이 스레드에서 만져도 된다. 비우지 않으면 재시작 후 그 방들이 창 끝 예약 없이
        // "창 열림"으로 남아 좋아요를 영원히 모으기만 한다.
        windows.clear();
        pending.set(0);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
