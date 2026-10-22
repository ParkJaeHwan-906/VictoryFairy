package com.skhynix.chat.gateway.service;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.ChatRoles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 방별 큐와 배처(CHAT-GC-87·88·91).
 *
 * <p>컨슈머 스레드는 {@link #enqueueMessage}·{@link #enqueueDeleted} 로 큐에 넣기만 한다. 배처 스레드가
 * {@code chat.gateway.batch-interval-ms} 마다 방별 큐를 통째로 떼어 내 프레임을 만들고 쓰기 풀에 맡긴다.
 * 배처 스레드도 소켓에 쓰지 않는다.
 *
 * <p>큐는 넣기·떼어 내기 모두 {@code compute} 람다 안에서 한다(레지스트리와 같은 이유 — 떼어 낸 직후의 큐에
 * 넣으면 그 원소가 맵 밖에 갇혀 사라진다). 그래서 큐 자체는 동기화 없는 리스트다.
 *
 * <p>샘플링 로직은 없다. {@code chat.gateway.sampling-threshold-per-sec} 은 어떤 값이어도 무시한다(CHAT-GC-93).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class RoomBatcher implements SmartLifecycle {

    public static final int PHASE = Integer.MAX_VALUE - 20;

    private final Map<String, List<GatewayItem>> queues = new ConcurrentHashMap<>();
    private final SseEmitterRegistry registry;
    private final SseFrameWriter writer;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public RoomBatcher(SseEmitterRegistry registry, SseFrameWriter writer, ChatProperties chatProperties) {
        this.registry = registry;
        this.writer = writer;
        this.intervalMs = Math.max(1L, chatProperties.gateway().batchIntervalMs());
    }

    /** @param view msgId 는 레코드 오프셋이어야 한다 */
    public void enqueueMessage(String gameId, Long senderId, ChatMessageView view) {
        append(gameId, new GatewayItem.Message(senderId, view));
    }

    public void enqueueDeleted(String gameId, long msgId) {
        append(gameId, new GatewayItem.Deleted(msgId));
    }

    private void append(String gameId, GatewayItem item) {
        queues.compute(gameId, (key, queue) -> {
            List<GatewayItem> current = (queue == null) ? new ArrayList<>() : queue;
            current.add(item);
            return current;
        });
    }

    /** 한 틱. 방마다 큐를 떼어 내 보낸다. 방 하나의 실패가 다른 방·다음 틱을 막지 않는다. */
    void flush() {
        for (String gameId : queues.keySet()) {
            List<GatewayItem> taken = new ArrayList<>();
            queues.computeIfPresent(gameId, (key, queue) -> {
                taken.addAll(queue);
                return null;
            });
            if (taken.isEmpty()) {
                continue;
            }
            try {
                dispatch(gameId, taken);
            } catch (RuntimeException e) {
                log.error("배치 전달 실패 gameId={} items={}", gameId, taken.size(), e);
            }
        }
    }

    /**
     * 도착 순서를 지키며 연속한 메시지는 {@code messages} 하나로, 툼스톤은 {@code deleted} 하나씩으로 보낸다.
     * 한 방은 한 파티션이라 큐 순서가 곧 offset 오름차순이다.
     */
    private void dispatch(String gameId, List<GatewayItem> items) {
        Set<ChatSubscription> subscriptions = registry.subscriptions(gameId);
        if (subscriptions.isEmpty()) {
            return;
        }
        List<GatewayItem.Message> run = new ArrayList<>();
        for (GatewayItem item : items) {
            if (item instanceof GatewayItem.Message message) {
                run.add(message);
                continue;
            }
            sendMessages(subscriptions, run);
            run.clear();
            if (item instanceof GatewayItem.Deleted deleted) {
                // 발신자 포함 전원(CHAT-GC-91)
                SseFrame frame = SseFrame.deleted(deleted.msgId());
                for (ChatSubscription subscription : subscriptions) {
                    writer.deliver(subscription, frame);
                }
            }
        }
        sendMessages(subscriptions, run);
    }

    private void sendMessages(Set<ChatSubscription> subscriptions, List<GatewayItem.Message> run) {
        if (run.isEmpty()) {
            return;
        }
        Set<Long> senders = new HashSet<>();
        List<ChatMessageView> all = new ArrayList<>(run.size());
        for (GatewayItem.Message message : run) {
            all.add(message.view());
            if (message.senderId() != null) {
                senders.add(message.senderId());
            }
        }
        SseFrame shared = SseFrame.messages(all, all.getLast().msgId());

        for (ChatSubscription subscription : subscriptions) {
            Long userAccountId = subscription.userAccountId();
            if (!senders.contains(userAccountId)) {
                writer.deliver(subscription, shared);
                continue;
            }
            // 자기 메시지는 뺀다(CHAT-GC-67·88). 비면 이 구독자에게는 아무 프레임도 보내지 않는다.
            List<ChatMessageView> mine = new ArrayList<>(run.size());
            for (GatewayItem.Message message : run) {
                if (!Objects.equals(message.senderId(), userAccountId)) {
                    mine.add(message.view());
                }
            }
            if (!mine.isEmpty()) {
                writer.deliver(subscription, SseFrame.messages(mine, mine.getLast().msgId()));
            }
        }
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat-gateway-batcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safeFlush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void safeFlush() {
        try {
            flush();
        } catch (RuntimeException e) {
            // 예외가 새면 ScheduledExecutorService 가 이후 실행을 조용히 멈춘다.
            log.error("배처 틱 실패", e);
        }
    }

    /** 컨슈머가 먼저 멈춘 뒤 불린다. 남은 큐를 한 번 더 비워 쓰기 풀에 넘기고 끝낸다. */
    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(intervalMs * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        safeFlush();
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
