package com.skhynix.quiz.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 퀴즈방 SSE 구독 레지스트리. {@link SseEmitterRegistry}(구단 채팅)와 <b>맵을 공유하지 않는다</b> — 그쪽
 * {@code register()} 의 last-one-wins 축출은 같은 계정의 구독을 방 불문 전부 끊으므로, 같은 레지스트리를 쓰면
 * 퀴즈방에 들어가는 순간 그 계정의 {@code /rt/chat} 스트림이 끊긴다. 여기 축출은 퀴즈방 구독끼리만이다.
 *
 * <p>버스 토픽은 {@link #topic(long)}({@code quiz-room:{roomId}})이다. 채팅 방 uid(UUID)와 겹치지 않아 수신 측이
 * 접두사 하나로 두 레지스트리를 가른다({@link #isTopic}). 채팅 쪽 축출 명령도 채팅 방 uid 로 나가므로 여기에 닿지 않는다.
 *
 * <p>맵 조작 규칙은 {@link SseEmitterRegistry} 와 같다 — 추가·제거를 {@code compute} 람다 안에서 끝내고,
 * {@code complete()} 는 람다 밖에서 부른다(그 콜백이 같은 맵을 재귀 갱신한다).
 */
@Component
public class QuizRoomSseRegistry {

    public static final String TOPIC_PREFIX = "quiz-room:";

    /** 아직 presence 를 한 번도 받지 못한 구독. 주기 전달이 아무 값과도 다르다고 보고 보낸다. */
    public static final long PRESENCE_UNKNOWN = -1L;
    /** 아직 Redis 등록을 거치지 않은 구독. 주기 전달이 건너뛴다(첫 값은 등록이 보낸다). */
    public static final long PRESENCE_NOT_JOINED = -2L;

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, Set<Subscription>> rooms = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();

    private volatile PresenceListener presenceListener = PresenceListener.NONE;

    public static String topic(long roomId) {
        return TOPIC_PREFIX + roomId;
    }

    public static boolean isTopic(String topic) {
        return topic != null && topic.startsWith(TOPIC_PREFIX);
    }

    public void setPresenceListener(PresenceListener presenceListener) {
        this.presenceListener = presenceListener;
    }

    public String instanceId() {
        return instanceId;
    }

    /** 등록한 뒤 같은 계정의 <b>다른</b> 퀴즈방 구독(같은 방의 이전 구독 포함)을 이 파드에서 끝낸다. */
    public Subscription register(long roomId, Long userAccountId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription = new Subscription(roomId, emitter, userAccountId);
        rooms.compute(roomId, (key, subscriptions) -> {
            Set<Subscription> current = (subscriptions == null) ? ConcurrentHashMap.newKeySet() : subscriptions;
            current.add(subscription);
            return current;
        });

        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(subscription);
        });
        emitter.onError(error -> remove(subscription));

        closeByUser(userAccountId, subscription);
        return subscription;
    }

    /** 명시적 퇴장 — 그 방의 그 계정 구독만. */
    public void closeSubscriptions(long roomId, Long userAccountId) {
        List<Subscription> closed = new ArrayList<>();
        collectAndDetach(roomId, userAccountId, null, closed);
        complete(closed);
    }

    public void close(Subscription subscription) {
        if (remove(subscription)) {
            complete(List.of(subscription));
        }
    }

    /** 버스로 받은 이벤트. 종료 신호는 연결 종료로, 나머지는 그 방 구독자 전송으로 간다. */
    public void deliver(String topic, RealtimeEvent event, SubscriptionCloseCommand command) {
        Long roomId = roomIdOf(topic);
        if (roomId == null) {
            return;
        }
        if (command != null) {
            handleCloseCommand(roomId, command);
            return;
        }
        publish(roomId, event);
    }

    public void publish(long roomId, RealtimeEvent event) {
        Set<Subscription> subscriptions = rooms.get(roomId);
        if (subscriptions == null) {
            return;
        }
        Long excluded = event.excludeUserAccountId();
        for (Subscription subscription : subscriptions) {
            if (excluded != null && excluded.equals(subscription.userAccountId())) {
                continue;
            }
            send(subscription, event.name(), event.data());
        }
    }

    /** @return 보냈으면 true. 실패한 구독은 회수한다. */
    public boolean send(Subscription subscription, String name, Object data) {
        try {
            subscription.emitter().send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (Exception e) {
            remove(subscription);
            return false;
        }
    }

    public Set<Long> roomIds() {
        return rooms.keySet();
    }

    public Set<Subscription> subscriptions(long roomId) {
        Set<Subscription> subscriptions = rooms.get(roomId);
        return subscriptions == null ? Set.of() : subscriptions;
    }

    @Scheduled(fixedRate = 15_000L)
    public void heartbeat() {
        for (Set<Subscription> subscriptions : rooms.values()) {
            for (Subscription subscription : subscriptions) {
                try {
                    subscription.emitter().send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(subscription);
                }
            }
        }
        presenceListener.heartbeat();
    }

    private void handleCloseCommand(long roomId, SubscriptionCloseCommand command) {
        if (instanceId.equals(command.originInstanceId())) {
            return;
        }
        if (command.allRooms()) {
            closeByUser(command.targetUserAccountId(), null);
        } else {
            closeSubscriptions(roomId, command.targetUserAccountId());
        }
    }

    private static Long roomIdOf(String topic) {
        if (!isTopic(topic)) {
            return null;
        }
        try {
            return Long.parseLong(topic.substring(TOPIC_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return 이번 호출이 맵에서 떼어 냈으면 true(퇴장 알림은 구독마다 한 번만 나간다). */
    private boolean remove(Subscription subscription) {
        boolean[] removed = {false};
        rooms.computeIfPresent(subscription.roomId(), (key, subscriptions) -> {
            removed[0] = subscriptions.remove(subscription);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
        if (removed[0]) {
            presenceListener.left(subscription);
        }
        return removed[0];
    }

    private void closeByUser(Long userAccountId, Subscription keep) {
        List<Subscription> closed = new ArrayList<>();
        for (Long roomId : rooms.keySet()) {
            collectAndDetach(roomId, userAccountId, keep, closed);
        }
        complete(closed);
    }

    private void collectAndDetach(long roomId, Long userAccountId, Subscription keep,
            List<Subscription> collected) {
        List<Subscription> detached = new ArrayList<>();
        rooms.computeIfPresent(roomId, (key, subscriptions) -> {
            List<Subscription> targets = subscriptions.stream()
                    .filter(subscription -> userAccountId.equals(subscription.userAccountId()))
                    .filter(subscription -> subscription != keep)
                    .toList();
            subscriptions.removeAll(targets);
            detached.addAll(targets);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
        // 퇴장 알림은 람다 밖에서 — 리스너가 맵을 다시 읽는다
        for (Subscription subscription : detached) {
            presenceListener.left(subscription);
        }
        collected.addAll(detached);
    }

    private void complete(List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 동일성은 인스턴스 기준이다(같은 계정·같은 방의 구독이 여럿일 수 있다). */
    public static final class Subscription {

        private final long roomId;
        private final SseEmitter emitter;
        private final Long userAccountId;
        private final AtomicLong presenceSent = new AtomicLong(PRESENCE_NOT_JOINED);

        Subscription(long roomId, SseEmitter emitter, Long userAccountId) {
            this.roomId = roomId;
            this.emitter = emitter;
            this.userAccountId = userAccountId;
        }

        public long roomId() {
            return roomId;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public Long userAccountId() {
            return userAccountId;
        }

        public long presenceSent() {
            return presenceSent.get();
        }

        public void presenceSent(long value) {
            presenceSent.set(value);
        }
    }

    /**
     * 구독 수명을 풀이 중 인원 집계에 알리는 통로. 컨테이너 콜백·스케줄러 스레드에서 불리므로 넘기기만 하고 바로 돌아와야 한다.
     * 생성자 주입이 아니라 setter 인 이유: 집계 쪽이 이 레지스트리를 읽어야 해서 생성자끼리 순환한다.
     */
    public interface PresenceListener {

        PresenceListener NONE = new PresenceListener() {
            @Override
            public void left(Subscription subscription) {
            }

            @Override
            public void heartbeat() {
            }
        };

        void left(Subscription subscription);

        void heartbeat();
    }
}
