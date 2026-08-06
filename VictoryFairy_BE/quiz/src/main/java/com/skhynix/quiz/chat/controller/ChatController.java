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

/**
 * 구단별 채팅 REST/SSE 엔드포인트. {@code /api/game/chat/**}는 quiz {@code SecurityConfig}에서 자동 인증
 * 필수이며, principal은 {@code JwtAuthenticationFilter}가 넣은 {@code Long userAccountId}다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/game 은 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/game/chat/**
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    /**
     * 방 목록(소프트 삭제 제외). {@code teamId}를 생략하면 요청자의 응원 구단으로 간주하고, 응원 구단과
     * 다른 값이면 403이다. {@code teamId}가 정수가 아니면 바인딩 단계에서 400이 나며 이 400은
     * {@code ApiResponse} 래퍼가 아니다(기존 공통 규약의 예외).
     */
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getRooms(
            @RequestParam(required = false) Long teamId,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRooms(teamId, userAccountId)));
    }

    /** 방 상세. 없거나 삭제된 방이면 404, 내 응원 구단 방이 아니면 403. */
    @GetMapping("/rooms/{roomUid}")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoom(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getRoom(roomUid, userAccountId)));
    }

    /** SSE 구독 스트림. 표준 {@code EventSource}는 헤더를 못 실어 fetch 기반 폴리필로 인증해야 한다. */
    @GetMapping(value = "/rooms/{roomUid}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        return chatService.subscribe(roomUid, userAccountId);
    }

    /**
     * 명시적 퇴장 — 이 방에 대한 내 SSE 구독을 끊는다. 끊을 구독이 없어도, 응원 구단이 없어도, 방이
     * 없거나 삭제됐어도 200이다(멱등). 구단 일치 검사를 걸지 않는 것이 계약이다 — 정리 요청을 막으면
     * 구단을 바꾼 사용자가 자기 낡은 연결을 닫지 못한다.
     */
    @DeleteMapping("/rooms/{roomUid}/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@PathVariable String roomUid,
            @AuthenticationPrincipal Long userAccountId) {
        chatService.unsubscribe(roomUid, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** 메시지 전송. 저장 후 발신자를 제외한 구독자에게 SSE로 전달하고 저장 메시지를 201로 반환. */
    @PostMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(@PathVariable String roomUid,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        MessageResponse response = chatService.sendMessage(roomUid, userAccountId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /** 히스토리(최신순 30건 페이징, blind·삭제 제외). 내 응원 구단 방이 아니면 403. */
    @GetMapping("/rooms/{roomUid}/messages")
    public ResponseEntity<ApiResponse<PageResponse<MessageResponse>>> getHistory(
            @PathVariable String roomUid,
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getHistory(roomUid, page, userAccountId)));
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
