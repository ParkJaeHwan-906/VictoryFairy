package com.skhynix.quiz.realtime;

/**
 * pub/sub 토픽(방)으로 발행되는 실시간 이벤트 한 건.
 *
 * @param name                 SSE 이벤트 이름(예: {@code message}). 구독자는 {@code event:} 필드로 구분한다.
 * @param data                 SSE {@code data:}로 직렬화될 payload. 메시지 이벤트는 식별자를 싣지 않는다.
 * @param excludeUserAccountId fan-out에서 제외할 구독자(발신자). 발신자에게는 에코하지 않으며 본인
 *                             메시지는 POST 응답으로만 렌더된다. 제외 대상이 없으면 {@code null}.
 */
public record RealtimeEvent(String name, Object data, Long excludeUserAccountId) {
}
