package com.skhynix.quiz.chat.dto;

import com.skhynix.domain.chat.entity.Chatroom;

public record RoomResponse(String roomUid, String team, String name) {

    public static RoomResponse of(Chatroom chatroom) {
        return new RoomResponse(
                chatroom.getUid(),
                chatroom.getTeam().getName(),
                chatroom.getName());
    }
}
