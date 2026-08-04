package com.skhynix.quiz.realtime;

/**
 * pub/sub 토픽(방)으로 발행되는 실시간 이벤트 한 건.
 *
 * @param excludeUserAccountId fan-out에서 제외할 구독자(발신자, 본인 메시지는 POST 응답으로만 렌더). 없으면 {@code null}.
 */
public record RealtimeEvent(String name, Object data, Long excludeUserAccountId) {
}
