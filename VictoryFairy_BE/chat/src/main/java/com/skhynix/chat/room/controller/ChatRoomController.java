package com.skhynix.chat.room.controller;

import com.skhynix.chat.room.dto.RoomResponse;
import com.skhynix.chat.room.service.ChatRoomService;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.common.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 접두사 /chat 은 server.servlet.context-path 가 붙인다. 외부 경로는 /chat/rooms/**
// 응원 구단 검사는 어느 경로에도 없다(CHAT-GC-13).
@RestController
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/rooms")
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRooms() {
        return ResponseEntity.ok(ApiResponse.ok(chatRoomService.getRooms()));
    }

    @GetMapping("/{gameId}")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoom(@PathVariable String gameId) {
        return ResponseEntity.ok(ApiResponse.ok(chatRoomService.getRoom(gameId)));
    }
}
