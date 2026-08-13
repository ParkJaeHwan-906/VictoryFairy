package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

public record MessageResponse(Long id, String content, String senderNickname, LocalDateTime createdAt) {

    public static MessageResponse from(Chat chat) {
        return new MessageResponse(
                chat.getId(),
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                chat.getCreatedAt());
    }
}
