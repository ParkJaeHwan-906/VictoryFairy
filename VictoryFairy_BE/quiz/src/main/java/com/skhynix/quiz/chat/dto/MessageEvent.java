package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

// profileImgUrl 은 MessageResponse 와 같은 값·같은 형태(BaseURL 을 뺀 EP, 없으면 null)다.
// 히스토리에만 실으면 SSE 로 방금 도착한 메시지만 아바타가 비었다가 새로고침해야 채워지므로 함께 싣는다.
public record MessageEvent(Long id, String content, String senderNickname, String profileImgUrl,
                           LocalDateTime createdAt, String roomUid) {

    public static MessageEvent of(Chat chat, String roomUid) {
        return new MessageEvent(
                chat.getId(),
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                // 전송 트랜잭션이 이미 로딩해 둔 발신자 계정이라 여기서 SELECT 가 추가되지 않는다.
                chat.getUserAccount().getProfileImgUrl(),
                chat.getCreatedAt(),
                roomUid);
    }
}
