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

/**
 * 방({@code roomUid})별 SSE 구독을 관리하는 인메모리 레지스트리. 구독 수({@link #count})는 파드별 값이라
 * 어떤 API 응답으로도 노출하지 않는다.
 *
 * <p>죽은 연결 회수: 주기적 하트비트({@code :ping} 주석)를 전송하고, 전송 실패로 감지된 연결을 정리한다.
 * onCompletion/onTimeout/onError 콜백에서도 해제한다.
 *
 * <p>서버 주도 종료({@link #closeSubscriptions}·{@link #closeAllSubscriptions})는 이 회수 경로를
 * <b>대체하지 않고 그 위에 얹힌다</b> — 퇴장 요청 없이 끊긴 연결은 종전대로 하트비트 실패·콜백·타임아웃이
 * 걷어간다.
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
     * 이 인스턴스(레지스트리)의 식별자. 종료 명령을 낸 파드가 되받은 자기 명령을 무시하는 데 쓴다
     * ({@link SubscriptionCloseCommand#originInstanceId()}).
     */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 방 구독을 등록하고 열린 {@link SseEmitter}를 반환한다. 연결 종료(완료·타임아웃·오류) 시 콜백에서
     * 구독을 해제한다.
     *
     * <p>같은 사용자의 <b>기존 구독은 방을 가리지 않고 전부 끊는다</b>(last-one-wins 축출).
     * 방금 넣은 구독은 identity로 지켜내므로 새 연결이 자기 축출에 휩쓸리지 않는다.
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

        // 축출은 등록이 끝난 뒤에 별도 compute로 처리한다 — 등록 람다 안에서 기존 구독을 complete()하면
        // 그 콜백(onCompletion → remove)이 같은 맵을 재귀 갱신해 CHM 계약을 깬다.
        closeByUser(userAccountId, subscription);
        return emitter;
    }

    /**
     * 지정 사용자의 <b>이 방</b> 구독을 끊는다(명시적 퇴장). 끊을 구독이 없으면 아무 상태도
     * 바꾸지 않는다(멱등).
     */
    public void closeSubscriptions(String roomUid, Long userAccountId) {
        List<Subscription> closed = new ArrayList<>();
        collectAndDetach(roomUid, userAccountId, null, closed);
        complete(closed);
    }

    /** 지정 사용자의 구독을 방과 무관하게 전부 끊는다(축출의 원격 처리분). */
    public void closeAllSubscriptions(Long userAccountId) {
        closeByUser(userAccountId, null);
    }

    /**
     * 버스로 받은 종료 명령을 처리한다.
     *
     * <p>명령을 낸 인스턴스는 이미 로컬을 동기적으로 정리했으므로 되받은 자기 명령을 무시한다 — prod 버스는
     * 발행 파드에도 되돌아오기 때문에, 무시하지 않으면 방금 연 새 구독이 자기 축출 명령에 끊긴다.
     */
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

    /** 종료 명령에 실을 이 인스턴스의 식별자. */
    public String instanceId() {
        return instanceId;
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

    /** 모든 방에서 지정 사용자의 구독을 떼어내고 끊는다. {@code keep}(방금 등록한 구독)은 남긴다. */
    private void closeByUser(Long userAccountId, Subscription keep) {
        List<Subscription> closed = new ArrayList<>();
        for (String roomUid : rooms.keySet()) {
            collectAndDetach(roomUid, userAccountId, keep, closed);
        }
        complete(closed);
    }

    /**
     * 한 방에서 대상 구독을 떼어내 {@code collected}에 모은다. 떼어내는 일과 빈 Set을 맵에서 걷어내는 일을
     * {@code computeIfPresent}로 같은 빈 잠금 아래 원자적으로 묶는다 — {@code get} 후 밖에서 지우면
     * {@code register}가 그 틈에 같은 Set을 집어가 고아 Set이 된다({@code register} 주석 참고).
     *
     * <p>실제 {@code complete()}는 람다 <b>밖</b>에서 한다({@link #complete} 주석).
     */
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

    /**
     * 서버 주도 종료. {@code complete()}는 onCompletion 콜백을 태우고 그 콜백이 {@link #remove}로 같은 맵을
     * 다시 갱신하므로 <b>compute 람다 안에서 부르면 안 된다</b>(ConcurrentHashMap은 재귀 갱신을 금지한다).
     * 이미 레지스트리에서 떼어낸 뒤라 그 콜백은 no-op이고, 끊긴 연결이면 예외가 날 수 있어 삼킨다.
     */
    private void complete(List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            try {
                subscription.emitter().complete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 한 건의 구독. 발신자 제외 fan-out을 위해 emitter와 구독자 id를 함께 보관한다. */
    private record Subscription(SseEmitter emitter, Long userAccountId) {
    }
}
