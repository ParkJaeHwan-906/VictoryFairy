package com.skhynix.chat.shared.redis;

/**
 * 좋아요 클릭 1회(속도 제한 통과분)의 pub/sub 메시지(CHAT-LK-21). 파드 간 내부 계약이다.
 *
 * <p>계정 id·닉네임·개수를 넣지 않는다 — 수신 측이 발신자를 알 이유가 없고, 있으면 이벤트로 샐 수 있다.
 *
 * @param teamCode {@code teams.code}(메시지 {@code teamCode} 와 같은 값 공간)
 */
public record LikeSignal(String gameId, String teamCode) {

    /**
     * 채널 하나만 쓴다. 키가 아니라 채널이라 TTL·자정 정리 대상이 아니다.
     * quiz 의 {@code realtime:events} 와 이름이 겹치지 않는다.
     */
    public static final String CHANNEL = "chat:likes";
}
