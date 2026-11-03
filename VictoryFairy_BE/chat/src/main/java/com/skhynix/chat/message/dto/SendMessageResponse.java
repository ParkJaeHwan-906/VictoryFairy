package com.skhynix.chat.message.dto;

/**
 * 202 본문(CHAT-GC-50). dedup 재반환도 이 본문을 그대로 돌려준다(CHAT-GC-106).
 *
 * @param msgId 그 레코드의 Kafka 오프셋. 연속이 아니다
 * @param content 마스킹 결과
 */
public record SendMessageResponse(String gameId, long msgId, String content) {
}
