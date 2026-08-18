package com.skhynix.quiz.realtime;

/**
 * <b>전송 이벤트가 아니다.</b> 전달 경로({@link InMemoryPublisher}, {@link RealtimeEventSubscriber})는
 * {@link #EVENT_NAME}으로 갈라 {@link SseEmitterRegistry#handleCloseCommand}로 보내야 한다 — 분기 없이
 * 기존 경로로 흘리면 이 명령이 구독자에게 {@code data:}로 나간다.
 */
public record SubscriptionCloseCommand(Long targetUserAccountId, String originInstanceId, boolean allRooms) {

    public static final String EVENT_NAME = "subscription-close";

    public static SubscriptionCloseCommand leave(Long targetUserAccountId, String originInstanceId) {
        return new SubscriptionCloseCommand(targetUserAccountId, originInstanceId, false);
    }

    public static SubscriptionCloseCommand evict(Long targetUserAccountId, String originInstanceId) {
        return new SubscriptionCloseCommand(targetUserAccountId, originInstanceId, true);
    }

    public static boolean isCloseSignal(RealtimeEvent event) {
        return EVENT_NAME.equals(event.name());
    }

    public RealtimeEvent toEvent() {
        return new RealtimeEvent(EVENT_NAME, this, null);
    }
}
