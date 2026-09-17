package com.skhynix.quiz.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterRegistry {

    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Set<Subscription>> rooms = new ConcurrentHashMap<>();

    private final String instanceId = UUID.randomUUID().toString();

    public SseEmitter register(String roomUid, Long userAccountId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription = new Subscription(emitter, userAccountId);
        // add까지 compute 람다 안에서 끝낸다(밖에서 add하면 마지막 퇴장으로 Set이 맵에서 걷힌 직후
        // 같은 Set에 들어가는 레이스가 나 그 구독이 fan-out·하트비트에서 통째로 누락된다).
        // 람다는 빈(bin) 잠금을 잡은 채 실행되니 I/O·블로킹 호출을 넣지 말 것.
        rooms.compute(roomUid, (key, subscriptions) -> {
            Set<Subscription> current = (subscriptions == null) ? ConcurrentHashMap.newKeySet() : subscriptions;
            current.add(subscription);
            return current;
        });

        emitter.onCompletion(() -> remove(roomUid, subscription));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(roomUid, subscription);
        });
        emitter.onError(error -> remove(roomUid, subscription));

        // 축출은 등록이 끝난 뒤에 별도 compute로 처리한다 — 등록 람다 안에서 기존 구독을 complete()하면
        // 그 콜백(onCompletion → remove)이 같은 맵을 재귀 갱신해 CHM 계약을 깬다.
        closeByUser(userAccountId, subscription);
        return emitter;
    }

    public void closeSubscriptions(String roomUid, Long userAccountId) {
        List<Subscription> closed = new ArrayList<>();
        collectAndDetach(roomUid, userAccountId, null, closed);
        complete(closed);
    }

    public void closeAllSubscriptions(Long userAccountId) {
        closeByUser(userAccountId, null);
    }

    public void handleCloseCommand(String roomUid, SubscriptionCloseCommand command) {
        if (instanceId.equals(command.originInstanceId())) {
            return;
        }
        if (command.allRooms()) {
            closeAllSubscriptions(command.targetUserAccountId());
        } else {
            closeSubscriptions(roomUid, command.targetUserAccountId());
        }
    }

    public String instanceId() {
        return instanceId;
    }

    public void publish(String roomUid, RealtimeEvent event) {
        Set<Subscription> subscriptions = rooms.get(roomUid);
        if (subscriptions == null) {
            return;
        }
        Long excluded = event.excludeUserAccountId();
        for (Subscription subscription : subscriptions) {
            if (excluded != null && excluded.equals(subscription.userAccountId())) {
                continue;
            }
            try {
                subscription.emitter().send(SseEmitter.event()
                        .name(event.name())
                        .data(event.data()));
            } catch (Exception e) {
                remove(roomUid, subscription);
            }
        }
    }

    public int count(String roomUid) {
        Set<Subscription> subscriptions = rooms.get(roomUid);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    @Scheduled(fixedRate = 15_000L)
    public void heartbeat() {
        for (Map.Entry<String, Set<Subscription>> entry : rooms.entrySet()) {
            for (Subscription subscription : entry.getValue()) {
                try {
                    subscription.emitter().send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(entry.getKey(), subscription);
                }
            }
        }
    }

    private void remove(String roomUid, Subscription subscription) {
        rooms.computeIfPresent(roomUid, (key, subscriptions) -> {
            subscriptions.remove(subscription);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    private void closeByUser(Long userAccountId, Subscription keep) {
        List<Subscription> closed = new ArrayList<>();
        for (String roomUid : rooms.keySet()) {
            collectAndDetach(roomUid, userAccountId, keep, closed);
        }
        complete(closed);
    }

    private void collectAndDetach(String roomUid, Long userAccountId, Subscription keep,
            List<Subscription> collected) {
        rooms.computeIfPresent(roomUid, (key, subscriptions) -> {
            List<Subscription> targets = subscriptions.stream()
                    .filter(subscription -> userAccountId.equals(subscription.userAccountId()))
                    .filter(subscription -> !subscription.equals(keep))
                    .toList();
            subscriptions.removeAll(targets);
            collected.addAll(targets);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    private void complete(List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
            }
        }
    }

    private record Subscription(SseEmitter emitter, Long userAccountId) {
    }
}
