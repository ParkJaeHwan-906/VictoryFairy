package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

/**
 * SSE {@code message} 이벤트의 {@code data:} payload.
 *
 * <p>{@code id}는 {@code Chat}의 내부 PK다. 실시간 전달은 최대 1회(at-most-once)라 놓친 메시지가 생길 수
 * 있고, 클라이언트는 히스토리를 다시 읽어 수렴한다 — 그때 <b>SSE로 이미 받은 메시지와 히스토리 항목을
 * 같은 것으로 알아보려면 식별자가 필요하다</b>(중복 렌더 방지·구멍 감지). 신고 경로
 * {@code POST .../messages/{messageId}/report}의 {@code messageId}도 이 값이다.
 *
 * @param id             메시지 식별자({@code Chat.id})
 * @param content        메시지 내용
 * @param senderNickname 발신자 닉네임
 * @param createdAt      생성 시각
 * @param roomUid        방 외부 식별자
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
