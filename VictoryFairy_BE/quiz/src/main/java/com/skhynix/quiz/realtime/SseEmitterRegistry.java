package com.skhynix.quiz.realtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 방({@code roomUid})별 SSE 구독을 관리하는 인메모리 레지스트리.
 *
 * <p>구독마다 {@code userAccountId}를 함께 보관해 발신자 제외 fan-out에 쓴다. 구독 수({@link #count})는
 * 이 파드의 인메모리 값일 뿐이라 어떤 API 응답으로도 노출하지 않는다.
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
     * 방 구독을 등록하고 열린 {@link SseEmitter}를 반환한다. 반환 즉시 이 방의 구독 수가 1 증가한다.
     * 연결 종료(완료·타임아웃·오류) 시 콜백에서 구독을 해제해 구독 수를 1 감소시킨다.
     */
    public SseEmitter register(String roomUid, Long userAccountId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription = new Subscription(emitter, userAccountId);
        // computeIfAbsent로 Set을 얻은 뒤 "바깥에서" add하면 안 된다(고아 Set 레이스):
        //   스레드 A(퇴장): 마지막 구독 제거 → Set이 비어 맵에서 그 Set을 걷어냄
        //   스레드 B(입장):   ↑ 바로 그 사이에 같은 Set을 얻어 add
        // 결과: B의 구독이 맵에서 떨어져 나간 Set에 들어가 publish의 fan-out과 heartbeat 순회에서
        // 통째로 누락된다(메시지 미수신 + 죽은 연결 미회수). add까지 compute 람다 안에서 끝내면
        // ConcurrentHashMap의 compute 계열이 같은 키에 대해 상호 배타적이므로 remove의 맵 정리와
        // 원자적으로 직렬화된다. 람다는 빈(bin) 잠금을 잡은 채 실행되니 I/O·블로킹 호출을 넣지 말 것.
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
     * 구독 하나를 해제하고, 그 방의 마지막 구독이었다면 방 매핑까지 걷어낸다.
     *
     * <p>Set에서 지우는 일과 맵에서 방을 걷어내는 일을 {@code get} + {@code rooms.remove}로 나눠 하면
     * 그 틈에 {@code register}가 같은 Set을 집어가 고아 Set이 된다({@code register} 주석 참고).
     * {@code computeIfPresent} 람다 안에서 둘을 함께 처리해 같은 빈(bin) 잠금 아래 원자적으로 만든다 —
     * {@code null}을 반환하면 매핑이 제거된다. 단순화한다고 {@code rooms.get(...)} 방식으로 되돌리지 말 것.
     *
     * <p>이미 해제된 구독에 대해 중복 호출돼도 무해하다: 매핑이 없으면 람다가 아예 실행되지 않고,
     * {@code Set.remove}는 없는 원소에 대해 false만 돌려줄 뿐 감소 같은 부작용이 없다.
     */
    private void remove(String roomUid, Subscription subscription) {
        rooms.computeIfPresent(roomUid, (key, subscriptions) -> {
            subscriptions.remove(subscription);
            return subscriptions.isEmpty() ? null : subscriptions;
        });
    }

    /**
     * 한 건의 구독. 발신자 제외 fan-out을 위해 emitter와 구독자 id를 함께 보관한다.
     * 같은 사용자의 멀티탭도 서로 다른 emitter라 각각 별개의 구독으로 잡힌다(연결 기준).
     */
    private record Subscription(SseEmitter emitter, Long userAccountId) {
    }
}
