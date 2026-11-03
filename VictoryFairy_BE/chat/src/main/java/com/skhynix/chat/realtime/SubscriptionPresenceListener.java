package com.skhynix.chat.realtime;

/**
 * 레지스트리가 구독 수명을 접속 인원 집계(CHAT-PR-*)에 알리는 통로. 구현은 게이트웨이의 {@code PresenceTracker} 하나다.
 *
 * <p>레지스트리 생성자에 넣지 않고 setter 로 거는 이유: 집계 쪽이 레지스트리를 읽어야 해서 생성자끼리 순환하고,
 * 레지스트리 생성자를 직접 부르는 테스트가 많다. 걸리지 않은 레지스트리는 {@link #NONE} 으로 동작한다.
 *
 * <p>{@link #left}·{@link #heartbeat} 는 컨테이너 콜백·하트비트 스케줄러 스레드에서 불린다 — 넘기기만 하고 바로 돌아와야 한다.
 */
public interface SubscriptionPresenceListener {

    SubscriptionPresenceListener NONE = new SubscriptionPresenceListener() {
        @Override
        public void joined(ChatSubscription subscription) {
        }

        @Override
        public void left(ChatSubscription subscription) {
        }

        @Override
        public void heartbeat() {
        }
    };

    /**
     * 구독이 {@code :connected} 를 낸 뒤 요청 스레드가 부른다. 인원 등록(소유자 기록 포함)이 Redis 에 반영될 때까지
     * 제한된 시간 기다린다 — 호출부가 그 뒤에 다른 파드 축출 명령을 내야 옛 구독의 종료 판정보다 소유자 기록이 앞선다.
     */
    void joined(ChatSubscription subscription);

    /** 구독이 레지스트리에서 떨어졌다. 구독마다 한 번만 불린다. */
    void left(ChatSubscription subscription);

    /** 하트비트 주기(CHAT-GC-32). 생존 갱신을 맡긴다. */
    void heartbeat();

    /** 레지스트리 {@code start()} 끝에서 불린다. */
    default void registryStarted() {
    }

    /**
     * 레지스트리 {@code stop()} 이 전 구독을 닫고 {@link #left} 를 다 알린 뒤 불린다. 그 퇴장들을 반영하고 내려간다(제한 시간 안에서).
     *
     * <p>⚠ 집계 쪽이 자기 SmartLifecycle 로 멈추면 안 된다. Spring 은 빈을 멈추기 전에 그 빈에 의존하는 빈을 먼저 멈춘다
     * (phase 무관). 집계기는 레지스트리·쓰기 풀을 주입받으므로 레지스트리보다 항상 먼저 멈춰, 레지스트리가 종료 때 닫는
     * 구독의 퇴장이 닫힌 실행기에 쌓여 버려졌다(CHAT-PR-8 위반). 그래서 집계기의 수명은 레지스트리가 직접 끌고 간다.
     */
    default void registryStopped() {
    }
}
