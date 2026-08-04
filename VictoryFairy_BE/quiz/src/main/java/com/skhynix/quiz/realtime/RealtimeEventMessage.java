package com.skhynix.quiz.realtime;

/**
 * 인스턴스 간 pub/sub 버스를 오가는 전송 표현. {@link RealtimeEvent}에 토픽({@code roomUid})을 더한 것.
 * {@code data}가 {@code Object}인 이유: Jackson이 발행 측 payload를 {@code Map}으로 되살려도 같은
 * ObjectMapper로 다시 직렬화하면 원본과 바이트가 동일해, 수신 측이 원래 타입을 몰라도 된다(타입 정보를
 * 함께 싣지 않는 이유이기도 하다).
 */
public record RealtimeEventMessage(String roomUid, String name, Object data, Long excludeUserAccountId) {

    public static RealtimeEventMessage of(String roomUid, RealtimeEvent event) {
        return new RealtimeEventMessage(roomUid, event.name(), event.data(), event.excludeUserAccountId());
    }

    public RealtimeEvent toEvent() {
        return new RealtimeEvent(name, data, excludeUserAccountId);
    }
}
