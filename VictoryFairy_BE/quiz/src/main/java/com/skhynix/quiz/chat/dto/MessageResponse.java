package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * 메시지 응답(전송 결과·히스토리 공용). {@code id}는 {@code Chat} PK로 SSE {@link MessageEvent}와 동일한
 * 값 — 히스토리 재조회 시 SSE로 이미 받은 메시지를 중복 렌더하지 않고 신고 API의 {@code messageId}로도
 * 쓰인다. 작성자 계정 PK는 노출하지 않는다(닉네임만, 요구사항 Q9).
 */
public record MessageResponse(Long id, String content, String senderNickname, LocalDateTime createdAt) {

    public static MessageResponse from(Chat chat) {
        return new MessageResponse(
                chat.getId(),
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                chat.getCreatedAt());
    }
}
