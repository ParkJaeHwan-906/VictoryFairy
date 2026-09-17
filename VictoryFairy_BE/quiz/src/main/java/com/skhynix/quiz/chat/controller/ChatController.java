package com.skhynix.quiz.chat.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.chat.dto.MessageResponse;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.chat.dto.RoomResponse;
import com.skhynix.quiz.chat.dto.SendMessageRequest;
import com.skhynix.quiz.chat.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
// 접두사 /rt 은 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/chat/**
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRooms(
            @RequestParam(required = false) Long teamId,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRooms(teamId, userAccountId)));
    }

    @GetMapping("/rooms/{roomUid}")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoom(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRoom(roomUid, userAccountId)));
    }

    @GetMapping(value = "/rooms/{roomUid}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        return chatService.subscribe(roomUid, userAccountId);
    }

    @DeleteMapping("/rooms/{roomUid}/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        chatService.unsubscribe(roomUid, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(@PathVariable String roomUid,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        MessageResponse response = chatService.sendMessage(roomUid, userAccountId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<PageResponse<MessageResponse>>> getHistory(
            @PathVariable String roomUid,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getHistory(roomUid, page, userAccountId)));
    }

    @PostMapping("/rooms/{roomUid}/messages/{messageId}/report")
    public ResponseEntity<ApiResponse<Void>> reportMessage(@PathVariable String roomUid,
            @PathVariable Long messageId,
            @AuthenticationPrincipal Long userAccountId) {
        chatService.reportMessage(roomUid, messageId, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
