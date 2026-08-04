package com.skhynix.quiz.realtime;

/**
 * 특정 사용자의 SSE 구독을 끊으라는 <b>제어 신호</b>. 명시적 퇴장과 중복 구독 축출이 다중 파드에 도달해야
 * 해서 기존 {@link RealtimeEventPublisher} 버스에 실어 보낸다.
 *
 * <p><b>전송 이벤트가 아니다.</b> 전달 경로({@link InMemoryPublisher}, {@link RealtimeEventSubscriber})는
 * {@link #EVENT_NAME}으로 갈라 {@link SseEmitterRegistry#handleCloseCommand}로 보내야 한다 — 분기 없이
 * 기존 경로로 흘리면 이 명령이 구독자에게 {@code data:}로 나간다.
 *
 * <p>대상 지정에 {@link RealtimeEvent#excludeUserAccountId()}를 쓰지 않는 이유: 그 필드는 "fan-out에서
 * <b>제외</b>할 사용자"라는 정반대 뜻이라(발신자 에코 방지) 재해석해 쓰면 두 경로가 한 필드를 반대 의미로
 * 공유하게 된다.
 *
 * @param targetUserAccountId 연결을 끊을 사용자
 * @param originInstanceId 명령을 낸 인스턴스의 {@link SseEmitterRegistry#instanceId()}. 발행 파드는 자기
 *        레지스트리를 이미 동기적으로 정리했으므로 되받은 명령을 무시한다 — 이 필드가 없으면 방금 연
 *        새 구독이 자기가 낸 축출 명령에 끊긴다(prod 버스는 발행 파드에도 되돌아온다)
 * @param allRooms {@code true}면 방을 가리지 않고 그 사용자의 구독 전부(축출), {@code false}면 토픽으로
 *        지정된 방만(퇴장)
 */
public record SubscriptionCloseCommand(Long targetUserAccountId, String originInstanceId, boolean allRooms) {

    /** {@link RealtimeEvent#name()}에 실리는 식별자. 수신 측 분기의 유일한 기준이다. */
    public static final String EVENT_NAME = "subscription-close";

    /** 명시적 퇴장 — 요청 대상 방에 한정한다. */
    public static SubscriptionCloseCommand leave(Long targetUserAccountId, String originInstanceId) {
        return new SubscriptionCloseCommand(targetUserAccountId, originInstanceId, false);
    }

    /**
     * 중복 구독 축출(last-one-wins) — 방을 가리지 않는다. 계약의 대상이 "그 사용자의 기존 구독"이라
     * 구단당 방이 여러 개가 되어도 그대로 성립해야 한다.
     */
    public static SubscriptionCloseCommand evict(Long targetUserAccountId, String originInstanceId) {
        return new SubscriptionCloseCommand(targetUserAccountId, originInstanceId, true);
    }

    /** 전달 경로가 "전송"과 "연결 종료"를 가르는 판정. */
    public static boolean isCloseSignal(RealtimeEvent event) {
        return EVENT_NAME.equals(event.name());
    }

    /** 버스에 실을 이벤트로 감싼다. {@code excludeUserAccountId}는 항상 {@code null}(fan-out 대상이 아니다). */
    public RealtimeEvent toEvent() {
        return new RealtimeEvent(EVENT_NAME, this, null);
    }
}
