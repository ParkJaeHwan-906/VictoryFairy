package com.skhynix.quiz.realtime;

/**
 * 인스턴스 간 pub/sub 버스를 오가는 전송 표현. {@link RealtimeEvent}에 토픽({@code roomUid})을 더한
 * 형태로, 발행 측이 JSON으로 직렬화해 채널에 싣고 각 인스턴스의 구독자가 되살려
 * {@link SseEmitterRegistry}로 넘긴다.
 *
 * <p>{@code data}가 {@code Object}인 이유: 발행 측은 {@code MessageEvent} 같은 레코드를 그대로 싣지만
 * 수신 측은 그 타입을 알 필요가 없다. Jackson이 {@code Map}으로 되살리고, SSE로 내보낼 때 같은
 * ObjectMapper가 다시 JSON으로 쓰므로 <b>같은 인스턴스에서 직접 전달된 경우와 바이트가 동일</b>하다.
 * (타입 정보를 함께 싣지 않는 이유이기도 하다 — 되살릴 필요가 없다.)
 */
public record RealtimeEventMessage(String roomUid, String name, Object data, Long excludeUserAccountId) {

    public static RealtimeEventMessage of(String roomUid, RealtimeEvent event) {
        return new RealtimeEventMessage(roomUid, event.name(), event.data(), event.excludeUserAccountId());
    }

    public RealtimeEvent toEvent() {
        return new RealtimeEvent(name, data, excludeUserAccountId);
    }
}
