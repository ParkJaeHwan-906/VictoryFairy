package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * 메시지 응답(전송 결과·히스토리 공용). 메시지 식별자·작성자 PK는 노출하지 않는다(요구사항 Q5).
 *
 * @param content        메시지 내용
 * @param senderNickname 발신자 {@code UserAccount.nickname}
 * @param createdAt      생성 시각
 */
public record MessageResponse(String content, String senderNickname, LocalDateTime createdAt) {

    public static MessageResponse from(Chat chat) {
        return new MessageResponse(
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                chat.getCreatedAt());
    }
}
