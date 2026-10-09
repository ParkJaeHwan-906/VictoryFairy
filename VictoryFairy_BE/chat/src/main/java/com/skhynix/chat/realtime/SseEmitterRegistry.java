package com.skhynix.chat.realtime;

import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 방별 SSE 구독 관리. quiz {@code realtime/SseEmitterRegistry} 를 복사해 온 것이다(앱 간 의존 금지, CHAT-GC-104).
 * quiz 쪽과 달리 소켓에 직접 쓰지 않고 {@link SseFrameWriter} 에 맡긴다(CHAT-GC-89).
 *
 * <p>파드 로컬이다. 그래서 구독을 받는 파드에는 게이트웨이 컨슈머가 있어야 한다(CHAT-GC-6) — 이 빈도 게이트웨이 역할이다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class SseEmitterRegistry implements SmartLifecycle {

    /** CHAT-GC-33. spring.mvc.async.request-timeout 을 이보다 낮추지 말 것. */
    static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 쓰기 풀보다 늦게 멈춘다 — 남은 프레임을 흘린 뒤 전 구독을 닫는다. */
    public static final int PHASE = Integer.MAX_VALUE - 40;

    private final Map<String, Set<ChatSubscription>> rooms = new ConcurrentHashMap<>();

    private final String instanceId = UUID.randomUUID().toString();

    private final SseFrameWriter writer;

    private volatile boolean running;

    public SseEmitterRegistry(SseFrameWriter writer) {
        this.writer = writer;
    }

    /**
     * @param recovering Last-Event-ID 복구를 할 구독이면 true — {@link SseFrameWriter#goLive} 전까지 실시간 프레임을 붙잡아 둔다
     */
    public ChatSubscription register(String gameId, Long userAccountId, boolean recovering) {
        ChatSseEmitter emitter = new ChatSseEmitter(EMITTER_TIMEOUT_MS);
        ChatSubscription subscription = new ChatSubscription(gameId, userAccountId, emitter, recovering);
        subscription.onDead(() -> close(subscription));
        // add까지 compute 람다 안에서 끝낸다(밖에서 add하면 마지막 퇴장으로 Set이 맵에서 걷힌 직후
        // 같은 Set에 들어가는 레이스가 나 그 구독이 fan-out·하트비트에서 통째로 누락된다).
        // 람다는 빈(bin) 잠금을 잡은 채 실행되니 I/O·블로킹 호출을 넣지 말 것.
        rooms.compute(gameId, (key, subscriptions) -> {
            Set<ChatSubscription> current = (subscriptions == null) ? ConcurrentHashMap.newKeySet() : subscriptions;
            current.add(subscription);
            return current;
        });

        emitter.onCompletion(() -> remove(gameId, subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(error -> remove(gameId, subscription));

        // 축출은 등록이 끝난 뒤에 별도 compute로 처리한다 — 등록 람다 안에서 기존 구독을 complete()하면
        // 그 콜백(onCompletion → remove)이 같은 맵을 재귀 갱신해 CHM 계약을 깬다.
        closeByUser(userAccountId, subscription);
        return subscription;
    }

    /** 명시적 퇴장 — 그 방의 그 사용자 구독만. */
    public void closeSubscriptions(String gameId, Long userAccountId) {
        List<ChatSubscription> closed = new ArrayList<>();
        collectAndDetach(gameId, userAccountId, null, closed);
        complete(closed);
    }

    /** 축출 — 방 불문 그 사용자의 전 구독. */
    public void closeAllSubscriptions(Long userAccountId) {
        closeByUser(userAccountId, null);
    }

    /**
     * chat-control 로 받은 종료 명령(CHAT-GC-92). 자기 인스턴스가 발행한 명령은 무시한다 — 무시하지 않으면 방금 연
     * 새 구독이 자기 축출 명령에 끊긴다(CHAT-GC-35).
     */
    public void handleCloseCommand(SubscriptionCloseCommand command) {
        if (instanceId.equals(command.originInstanceId()) || command.targetUserAccountId() == null) {
            return;
        }
        if (command.allRooms()) {
            closeAllSubscriptions(command.targetUserAccountId());
        } else if (command.gameId() != null) {
            closeSubscriptions(command.gameId(), command.targetUserAccountId());
        }
    }

    public String instanceId() {
        return instanceId;
    }

    /** 팬아웃 대상. 반환된 Set 은 동시 수정 중에도 안전하게 순회할 수 있다. */
    public Set<ChatSubscription> subscriptions(String gameId) {
        Set<ChatSubscription> subscriptions = rooms.get(gameId);
        return subscriptions == null ? Set.of() : subscriptions;
    }

    public int count(String gameId) {
        Set<ChatSubscription> subscriptions = rooms.get(gameId);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    /**
     * 15초 하트비트(CHAT-GC-32). 대기열에 넣기만 한다 — 실패한 연결은 쓰기 풀이 회수한다(CHAT-GC-36).
     * 이 스케줄러 스레드는 방 수명 작업과 공유하므로 여기서 소켓에 쓰지 않는다.
     */
    @Scheduled(fixedRate = 15_000L)
    public void heartbeat() {
        for (Set<ChatSubscription> subscriptions : rooms.values()) {
            for (ChatSubscription subscription : subscriptions) {
                writer.enqueue(subscription, SseFrame.PING);
            }
        }
    }

    /** 쓰기 실패·쓰기 타임아웃·대기열 초과·SSE 타임아웃 — 맵에서 떼고 닫는다. */
    private void close(ChatSubscription subscription) {
        remove(subscription.gameId(), subscription);
        complete(List.of(subscription));
    }

    private void remove(String gameId, ChatSubscription subscription) {
        subscription.markClosed();
        rooms.computeIfPresent(gameId, (key, subscriptions) -> {
            subscriptions.remove(subscription);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    private void closeByUser(Long userAccountId, ChatSubscription keep) {
        List<ChatSubscription> closed = new ArrayList<>();
        for (String gameId : rooms.keySet()) {
            collectAndDetach(gameId, userAccountId, keep, closed);
        }
        complete(closed);
    }

    private void collectAndDetach(String gameId, Long userAccountId, ChatSubscription keep,
            List<ChatSubscription> collected) {
        rooms.computeIfPresent(gameId, (key, subscriptions) -> {
            List<ChatSubscription> targets = subscriptions.stream()
                    .filter(subscription -> userAccountId.equals(subscription.userAccountId()))
                    .filter(subscription -> !subscription.equals(keep))
                    .toList();
            subscriptions.removeAll(targets);
            collected.addAll(targets);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    // complete() 는 반드시 compute 람다 밖에서 부른다 — onCompletion → remove 가 같은 맵을 재귀 갱신한다.
    private void complete(List<ChatSubscription> subscriptions) {
        for (ChatSubscription subscription : subscriptions) {
            subscription.markClosed();
            writer.complete(subscription.emitter());
        }
    }

    @Override
    public void start() {
        running = true;
    }

    /** 종료 시 전 구독을 닫는다. 열린 SSE 가 웹 서버 graceful shutdown 을 타임아웃까지 붙잡지 않게 한다. */
    @Override
    public void stop() {
        running = false;
        List<ChatSubscription> all = new ArrayList<>();
        for (String gameId : rooms.keySet()) {
            rooms.computeIfPresent(gameId, (key, subscriptions) -> {
                all.addAll(subscriptions);
                return null;
            });
        }
        complete(all);
        log.info("컨텍스트 종료로 SSE 구독 {}건을 닫았다", all.size());
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
