package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * SSE {@code message} 이벤트의 {@code data:} payload. {@code id}는 {@code Chat} PK — 실시간 전달은
 * at-most-once라 클라이언트가 히스토리 재조회로 수렴하는데, 그때 SSE로 이미 받은 메시지를 식별해 중복
 * 렌더를 막는 데 쓰인다. 신고 API의 {@code messageId}도 이 값이다.
 */
public record MessageEvent(Long id, String content, String senderNickname, LocalDateTime createdAt,
                           String roomUid) {

    public static MessageEvent of(Chat chat, String roomUid) {
        return new MessageEvent(
                chat.getId(),
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                chat.getCreatedAt(),
                roomUid);
    }
}
