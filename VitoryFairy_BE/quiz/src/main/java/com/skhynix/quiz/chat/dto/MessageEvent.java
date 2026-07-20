package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * SSE {@code message} 이벤트의 {@code data:} payload. 요구사항 Q9 형식 {@code {content, senderNickname,
 * createdAt, roomUid}}를 그대로 따르며 메시지 식별자는 싣지 않는다.
 *
 * @param content        메시지 내용
 * @param senderNickname 발신자 닉네임
 * @param createdAt      생성 시각
 * @param roomUid        방 외부 식별자
 */
public record MessageEvent(String content, String senderNickname, LocalDateTime createdAt, String roomUid) {

    public static MessageEvent of(Chat chat, String roomUid) {
        return new MessageEvent(
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                chat.getCreatedAt(),
                roomUid);
    }
}
