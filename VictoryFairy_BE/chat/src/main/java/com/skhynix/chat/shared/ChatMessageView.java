package com.skhynix.chat.shared;

import com.skhynix.chat.shared.kafka.ChatMessagePayload;

/**
 * 외부로 나가는 메시지 항목. 히스토리(CHAT-GC-73)와 SSE {@code messages} 배열(CHAT-GC-87)이 같은 6필드를 쓴다.
 * 계정 id 는 싣지 않는다(CHAT-GC-14).
 */
public record ChatMessageView(
        long msgId,
        String content,
        String senderNickname,
        String teamCode,
        String profileImgUrl,
        String sentAt) {

    public static ChatMessageView of(ChatMessagePayload payload, long offset) {
        return new ChatMessageView(offset, payload.content(), payload.senderNickname(), payload.teamCode(),
                payload.profileImgUrl(), payload.sentAt());
    }
}
