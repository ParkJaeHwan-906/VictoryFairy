package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

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
