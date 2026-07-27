package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * 메시지 응답(전송 결과·히스토리 공용).
 *
 * <p>{@code id}는 {@code Chat}의 내부 PK로, SSE {@link MessageEvent}가 싣는 값과 같다. 히스토리에도
 * 같은 식별자가 있어야 클라이언트가 (1) SSE로 이미 그린 메시지를 히스토리 재조회에서 중복 렌더하지 않고,
 * (2) 신고 경로 {@code POST .../messages/{messageId}/report}를 호출할 수 있다. 작성자 계정 PK는 여전히
 * 노출하지 않는다(닉네임만 — 요구사항 Q9).
 *
 * @param id             메시지 식별자({@code Chat.id})
 * @param content        메시지 내용
 * @param senderNickname 발신자 {@code UserAccount.nickname}
 * @param createdAt      생성 시각
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
