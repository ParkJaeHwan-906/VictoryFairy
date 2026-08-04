package com.skhynix.quiz.realtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 방({@code roomUid})별 SSE 구독을 관리하는 인메모리 레지스트리. 구독 수({@link #count})는 파드별 값이라
 * 어떤 API 응답으로도 노출하지 않는다.
 *
 * <p>죽은 연결 회수: 주기적 하트비트({@code :ping} 주석)를 전송하고, 전송 실패로 감지된 연결을 정리한다.
 * onCompletion/onTimeout/onError 콜백에서도 해제한다.
 */
@Component
public class SseEmitterRegistry {

    /**
     * SSE 연결 타임아웃(밀리초). 하트비트가 살아 있는 연결을 유지하고, 무응답 연결은 이 시점에 만료돼
     * onTimeout으로 회수된다.
     */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, Set<Subscription>> rooms = new ConcurrentHashMap<>();

    /**
     * 방 구독을 등록하고 열린 {@link SseEmitter}를 반환한다. 연결 종료(완료·타임아웃·오류) 시 콜백에서
     * 구독을 해제한다.
     */
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
        return emitter;
    }

    /**
     * 방 구독자에게 이벤트를 전달한다. {@code excludeUserAccountId}(발신자)의 구독은 fan-out에서 제외한다.
     * 전송 실패 구독은 죽은 연결로 보고 즉시 회수한다.
     */
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

    /**
     * 이 파드가 들고 있는 방의 현재 구독 수. 구독이 없으면 0.
     *
     * <p>응답으로 나가지 않는다 — 파드별 값이라 다중 파드에서는 전역 참여 인원과 다르다. 레지스트리
     * 상태를 들여다보는 접근자로만 쓴다(테스트·진단).
     */
    public int count(String roomUid) {
        Set<Subscription> subscriptions = rooms.get(roomUid);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    /**
     * 열린 모든 구독에 하트비트({@code :ping} 주석 프레임)를 보낸다. 전송이 실패하면 leave 신호 없이
     * 끊긴 죽은 연결로 보고 회수한다 — 회수하지 않으면 fan-out이 매번 죽은 emitter를 타게 된다.
     */
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

    /**
     * 구독 하나를 해제하고, 그 방의 마지막 구독이었다면 방 매핑까지 걷어낸다. {@code get}+{@code remove}로
     * 나눠 하면 그 틈에 {@code register}가 같은 Set을 집어가 고아 Set이 된다 — {@code computeIfPresent}로
     * 같은 빈 잠금 아래 원자적으로 처리한다({@code register} 주석 참고).
     */
    private void remove(String roomUid, Subscription subscription) {
        rooms.computeIfPresent(roomUid, (key, subscriptions) -> {
            subscriptions.remove(subscription);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    /** 한 건의 구독. 발신자 제외 fan-out을 위해 emitter와 구독자 id를 함께 보관한다. */
    private record Subscription(SseEmitter emitter, Long userAccountId) {
    }
}
