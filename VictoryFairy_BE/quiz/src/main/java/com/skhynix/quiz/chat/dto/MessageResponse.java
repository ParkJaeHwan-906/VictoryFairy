package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chat;
import java.time.LocalDateTime;

// profileImgUrl 은 BaseURL 을 뺀 EP 다(user 의 GET /api/users/me 와 문자 그대로 같은 형태) — 클라이언트가
// 앞에 도메인을 붙여 쓴다. 없으면 null 이며 빈 문자열도 기본 이미지 URL 도 아니다.
// 탈퇴 계정 메시지는 (알수없음) 더미 계정으로 이관되고(ChatRepository.reassignSender) 그 계정은
// 프로필이 없어 자연히 null 이 나간다 — 별도 분기가 없는 것이 정상이다.
public record MessageResponse(Long id, String content, String senderNickname, String profileImgUrl,
                              LocalDateTime createdAt) {

    public static MessageResponse from(Chat chat) {
        return new MessageResponse(
                chat.getId(),
                chat.getContent(),
                chat.getUserAccount().getNickname(),
                // nickname 과 같은 users_account 행의 값이라 조회가 늘지 않는다(히스토리는 fetch join 으로,
                // 전송은 이미 로딩한 sender 엔티티로 이 시점에 계정이 이미 초기화되어 있다).
                chat.getUserAccount().getProfileImgUrl(),
                chat.getCreatedAt());
    }
}
