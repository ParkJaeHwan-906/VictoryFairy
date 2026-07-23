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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구단별 채팅 REST/SSE 엔드포인트. {@code /api/chat/**}는 quiz {@code SecurityConfig}에서 자동 인증
 * 필수이며, principal은 {@code JwtAuthenticationFilter}가 넣은 {@code Long userAccountId}다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    /** 방 목록(소프트 삭제 제외). */
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRooms() {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRooms()));
    }

    /** 방 상세. 없거나 삭제된 방이면 404. */
    @GetMapping("/rooms/{roomUid}")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoom(@PathVariable String roomUid) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRoom(roomUid)));
    }

    /**
     * SSE 구독 스트림. fetch 기반 EventSource 폴리필로 {@code Authorization} 헤더를 실어야 인증된다.
     * 구독 성립 시 participants +1, 연결 종료 시 -1(레지스트리 콜백).
     */
    @GetMapping(value = "/rooms/{roomUid}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        return chatService.subscribe(roomUid, userAccountId);
    }

    /**
     * 메시지 전송. content 검증({@code @Valid})은 컨트롤러 진입 전에 수행돼 위반 시 400이 방 미존재
     * 404보다 먼저 판정된다. 저장 후 발신자를 제외한 구독자에게 SSE로 전달하고 저장 메시지를 201로 반환.
     */
    @PostMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(@PathVariable String roomUid,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        MessageResponse response = chatService.sendMessage(roomUid, userAccountId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** 히스토리(최신순 30건 페이징, blind·삭제 제외). */
    @GetMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<PageResponse<MessageResponse>>> getHistory(
            @PathVariable String roomUid,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getHistory(roomUid, page)));
    }

    /**
     * 메시지 신고 → 즉시 blind(멱등). 자기 신고 403, 삭제 메시지 404, 이미 blind면 no-op 200.
     */
    @PostMapping("/rooms/{roomUid}/messages/{messageId}/report")
    public ResponseEntity<ApiResponse<Void>> reportMessage(@PathVariable String roomUid,
            @PathVariable Long messageId,
            @AuthenticationPrincipal Long userAccountId) {
        chatService.reportMessage(roomUid, messageId, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
