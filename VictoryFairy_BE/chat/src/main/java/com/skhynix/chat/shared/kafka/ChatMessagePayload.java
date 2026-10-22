package com.skhynix.chat.shared.kafka;

/**
 * chat-messages 레코드의 value(CHAT-GC-63). 필드 7개 고정.
 *
 * <p><b>msgId 는 여기 없다</b> — 레코드 오프셋이 msgId 다. 소비자는 {@code ConsumerRecord.offset()} 에서 읽는다.
 * {@code senderId} 는 내부용이라 외부 응답·SSE 로 내보내지 않는다(CHAT-GC-14) — 밖으로 나갈 땐
 * {@link com.skhynix.chat.shared.ChatMessageView} 로 바꾼다.
 *
 * @param teamCode 발신자의 전송 시점 응원 구단 {@code teams.code}, 없으면 null
 * @param profileImgUrl BaseURL 없는 EP, 없으면 null
 * @param content 마스킹 결과(원문은 어디에도 싣지 않는다)
 * @param sentAt 서버 수신 시각, 오프셋 포함 ISO-8601 문자열
 */
public record ChatMessagePayload(
        String gameId,
        Long senderId,
        String senderNickname,
        String teamCode,
        String profileImgUrl,
        String content,
        String sentAt) {
}
