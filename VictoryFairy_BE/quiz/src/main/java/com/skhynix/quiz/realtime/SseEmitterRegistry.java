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
 * <p>구독마다 {@code userAccountId}를 함께 보관해 (1) 발신자 제외 fan-out과 (2) participants 계산에
 * 쓴다. participants는 별도 DB 컬럼이 아니라 이 레지스트리의 현재 구독 수로 서빙된다 — connect/disconnect
 * 마다 DB write를 유발하지 않기 위한 best-effort 방식이며, 단일 인스턴스(현재 InMemory)에서 정확하다.
 *
 * <p>죽은 연결 회수: 주기적 하트비트({@code :ping} 주석)를 전송하고, 전송 실패로 감지된 연결을 정리해
 * participants를 보정한다. onCompletion/onTimeout/onError 콜백에서도 해제한다.
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
     * 방 구독을 등록하고 열린 {@link SseEmitter}를 반환한다. 반환 즉시 participants(=구독 수)가 1 증가한다.
     * 연결 종료(완료·타임아웃·오류) 시 콜백에서 구독을 해제해 participants를 1 감소시킨다.
     */
    public SseEmitter register(String roomUid, Long userAccountId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Subscription subscription = new Subscription(emitter, userAccountId);
        rooms.computeIfAbsent(roomUid, key -> ConcurrentHashMap.newKeySet()).add(subscription);

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
     * 방의 현재 구독 수(=participants). 구독이 없으면 0.
     */
    public int count(String roomUid) {
        Set<Subscription> subscriptions = rooms.get(roomUid);
        return subscriptions == null ? 0 : subscriptions.size();
    }

    /**
     * 열린 모든 구독에 하트비트({@code :ping} 주석 프레임)를 보낸다. 전송이 실패하면 leave 신호 없이
     * 끊긴 죽은 연결로 보고 회수해 participants를 보정한다(best-effort 근사 정확도).
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

    private void remove(String roomUid, Subscription subscription) {
        Set<Subscription> subscriptions = rooms.get(roomUid);
        if (subscriptions == null) {
            return;
        }
        subscriptions.remove(subscription);
        if (subscriptions.isEmpty()) {
            rooms.remove(roomUid);
        }
    }

    /**
     * 한 건의 구독. 발신자 제외 fan-out과 participants 집계를 위해 emitter와 구독자 id를 함께 보관한다.
     * 같은 사용자의 멀티탭도 서로 다른 emitter라 각각 별개의 구독으로 카운트된다(연결 기준).
     */
    private record Subscription(SseEmitter emitter, Long userAccountId) {
    }
}
