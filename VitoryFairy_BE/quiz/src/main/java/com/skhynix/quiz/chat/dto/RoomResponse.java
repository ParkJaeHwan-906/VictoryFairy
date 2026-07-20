package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chatroom;

/**
 * 채팅방 응답. 외부 식별자는 {@code roomUid}(36자 UUID)뿐이며 순차 PK는 노출하지 않는다.
 * {@code participants}는 현재 SSE 구독 수(best-effort)로 채워진다.
 *
 * @param roomUid      방 외부 식별자
 * @param team         구단(팀) 이름
 * @param name         방 이름
 * @param participants 현재 참여 인원(구독 수)
 */
public record RoomResponse(String roomUid, String team, String name, int participants) {

    public static RoomResponse of(Chatroom chatroom, int participants) {
        return new RoomResponse(
                chatroom.getUid(),
                chatroom.getTeam().getName(),
                chatroom.getName(),
                participants);
    }
}
