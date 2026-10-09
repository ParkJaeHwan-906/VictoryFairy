package com.skhynix.chat.realtime;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.ChatRoles;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * emitter 쓰기 전용 스레드풀(CHAT-GC-89)과 쓰기 타임아웃 감시(CHAT-GC-90).
 *
 * <p>Kafka 컨슈머·배처·요청 스레드는 여기에 프레임을 맡기기만 하고 소켓에 쓰지 않는다. 느린 클라이언트 하나가
 * 컨슈머 랙을 늘리거나 같은 방의 다른 구독자 전달을 막지 않게 하기 위해서다.
 *
 * <p>쓰기 하나가 {@code chat.gateway.write-timeout-ms} 를 넘기면 감시 스레드가 그 구독만 회수한다. 막힌 쓰기 스레드를
 * 강제로 깨우지는 않는다 — 그 스레드는 컨테이너 소켓 쓰기 타임아웃까지 묶여 있을 수 있다(회수 후 그 구독자에게 더
 * 쓰지 않을 뿐이다). 그런 클라이언트가 풀 크기만큼 동시에 생기면 다른 구독자 전달이 그만큼 밀린다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class SseFrameWriter implements SmartLifecycle {

    /** 구독자 하나에 쌓아 둘 수 있는 프레임 수. 넘치면 따라오지 못하는 클라이언트로 보고 끊는다. */
    static final int MAX_PENDING_FRAMES = 256;

    /**
     * 정지 순서: 컨슈머 → 배처 → 쓰기 풀 → 레지스트리(전 구독 종료). 높은 phase 가 먼저 멈춘다.
     * 전부 웹 서버 graceful shutdown 단계(DEFAULT_PHASE - 1024)보다 높아야 열린 SSE 가 그 단계를 붙잡지 않는다.
     */
    public static final int PHASE = Integer.MAX_VALUE - 30;

    private static final long SHUTDOWN_AWAIT_MS = 2_000L;

    private final long writeTimeoutNanos;
    private final long watchdogPeriodMs;
    private final int threads;
    // stop() 뒤 start() 로 다시 살 수 있어야 한다(컨텍스트 재시작 — 테스트 컨텍스트 캐시가 실제로 그렇게 한다).
    // 종료된 실행기를 재사용하면 start() 가 RejectedExecutionException 으로 기동을 깬다.
    private volatile ThreadPoolExecutor writePool;
    private volatile ScheduledExecutorService watchdog;
    private volatile ExecutorService closer;
    private final Set<ChatSubscription> writing = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public SseFrameWriter(ChatProperties chatProperties) {
        long writeTimeoutMs = chatProperties.gateway().writeTimeoutMs();
        this.writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(writeTimeoutMs);
        this.watchdogPeriodMs = Math.max(50L, writeTimeoutMs / 4);
        this.threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        createExecutors();
    }

    // 생성자에서도 만든다 — 웹 서버가 이 빈의 start() 보다 먼저 떠서 그 사이 들어온 구독도 쓸 수 있어야 한다.
    private void createExecutors() {
        this.writePool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), named("chat-sse-writer"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(named("chat-sse-watchdog"));
        // 쓰기 중이라 바로 닫지 못한 emitter 를 대신 닫는다. 그 쓰기가 끝날 때까지 이 스레드가 기다린다.
        this.closer = Executors.newSingleThreadExecutor(named("chat-sse-closer"));
    }

    /**
     * 구독 직후 첫 프레임({@code :connected}). 등록 바로 뒤, 복구 프레임보다 먼저 불러야 한다.
     *
     * <p>⚠ 서블릿 응답 헤더는 첫 쓰기·flush 때 나간다. 이 프레임이 없으면 놓친 것이 없는 구독은 첫 하트비트(최대 15초)
     * 까지 클라이언트가 200 을 못 받아 연결 중 상태로 매달린다. 대기열을 거치므로 이후 프레임과 순서가 뒤집히지 않는다.
     */
    public void open(ChatSubscription subscription) {
        enqueue(subscription, SseFrame.CONNECTED);
    }

    /** 복구·하트비트 프레임. 복구 중에도 바로 대기열에 들어간다. */
    public void enqueue(ChatSubscription subscription, SseFrame frame) {
        submit(subscription, frame, false);
    }

    /** 게이트웨이 실시간 프레임. 복구 중이면 복구가 끝날 때까지 붙잡아 둔다. */
    public void deliver(ChatSubscription subscription, SseFrame frame) {
        submit(subscription, frame, true);
    }

    /** 복구가 끝났다. 붙잡아 둔 실시간 프레임을 흘린다. */
    public void goLive(ChatSubscription subscription) {
        if (subscription.goLive()) {
            schedule(subscription);
        }
    }

    /**
     * emitter 를 닫는다. 쓰기 중이라 잠금을 못 얻으면 닫기를 closer 스레드로 넘겨 호출 스레드는 기다리지 않는다.
     */
    public void complete(ChatSseEmitter emitter) {
        if (tryComplete(emitter)) {
            return;
        }
        try {
            closer.execute(() -> completeBlocking(emitter));
        } catch (RejectedExecutionException e) {
            // 종료 중. 컨테이너가 연결을 정리한다.
            log.debug("종료 중이라 emitter 닫기를 넘기지 못함");
        }
    }

    private void submit(ChatSubscription subscription, SseFrame frame, boolean realtime) {
        switch (subscription.offer(frame, realtime, MAX_PENDING_FRAMES)) {
            case CLAIMED -> schedule(subscription);
            case OVERFLOW -> {
                log.info("SSE 대기열 초과로 구독 종료 gameId={} userAccountId={}",
                        subscription.gameId(), subscription.userAccountId());
                subscription.die();
            }
            case QUEUED, DROPPED -> {
            }
        }
    }

    private void schedule(ChatSubscription subscription) {
        try {
            writePool.execute(() -> drain(subscription));
        } catch (RejectedExecutionException e) {
            subscription.releaseDrain();
        }
    }

    private void drain(ChatSubscription subscription) {
        SseFrame frame;
        while ((frame = subscription.next()) != null) {
            subscription.writeStarted(System.nanoTime());
            writing.add(subscription);
            try {
                frame.writeTo(subscription.emitter());
            } catch (Exception e) {
                // 끊긴 연결(하트비트 실패 포함, CHAT-GC-36)·이미 닫힌 emitter. 회수만 한다.
                log.debug("SSE 쓰기 실패 gameId={} userAccountId={}", subscription.gameId(),
                        subscription.userAccountId(), e);
                subscription.die();
            } finally {
                writing.remove(subscription);
                subscription.writeFinished();
            }
        }
    }

    void checkWriteTimeouts() {
        long now = System.nanoTime();
        for (ChatSubscription subscription : writing) {
            long started = subscription.writeStartedNanos();
            if (started != 0L && now - started > writeTimeoutNanos) {
                log.info("SSE 쓰기 타임아웃으로 구독 종료 gameId={} userAccountId={}",
                        subscription.gameId(), subscription.userAccountId());
                writing.remove(subscription);
                subscription.die();
            }
        }
    }

    private static boolean tryComplete(ChatSseEmitter emitter) {
        try {
            return emitter.tryComplete();
        } catch (Exception e) {
            return true; // 이미 닫혔다
        }
    }

    private static void completeBlocking(ChatSseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 이미 닫혔다
        }
    }

    @Override
    public void start() {
        if (writePool.isShutdown()) {
            createExecutors();
        }
        watchdog.scheduleWithFixedDelay(this::safeCheck, watchdogPeriodMs, watchdogPeriodMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void safeCheck() {
        try {
            checkWriteTimeouts();
        } catch (RuntimeException e) {
            // 예외가 새면 ScheduledExecutorService 가 이후 실행을 조용히 멈춘다.
            log.error("SSE 쓰기 감시 실패", e);
        }
    }

    /** 이미 맡은 프레임을 잠깐 흘려 보낸 뒤 풀을 내린다. */
    @Override
    public void stop() {
        running = false;
        writePool.shutdown();
        try {
            if (!writePool.awaitTermination(SHUTDOWN_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                writePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            writePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        watchdog.shutdownNow();
        closer.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    static ThreadFactory named(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
