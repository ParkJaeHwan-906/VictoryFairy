package com.skhynix.chat.message.controller;

import com.skhynix.chat.message.dto.HistoryResponse;
import com.skhynix.chat.message.dto.SendMessageRequest;
import com.skhynix.chat.message.dto.SendMessageResponse;
import com.skhynix.chat.message.service.ChatHistoryService;
import com.skhynix.chat.message.service.ChatMessageSendService;
import com.skhynix.chat.message.service.ChatReportService;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/rooms")
public class ChatMessageController {

    private final ChatMessageSendService sendService;
    private final ChatHistoryService historyService;
    private final ChatReportService reportService;

    // @Valid 를 붙이지 않는다. 방 존재(404)가 본문 검증(400)보다 먼저여야 해서 서비스가 검증한다(CHAT-GC-51).
    @PostMapping("/{gameId}/messages")
    public ResponseEntity<ApiResponse<SendMessageResponse>> send(@PathVariable String gameId,
            @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Long userAccountId) throws MethodArgumentNotValidException {
        SendMessageResponse response = sendService.send(gameId, userAccountId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @GetMapping("/{gameId}/messages")
    public ResponseEntity<ApiResponse<HistoryResponse>> getHistory(@PathVariable String gameId,
            @RequestParam(required = false) Long cursor,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getHistory(gameId, cursor, userAccountId)));
    }

    @PostMapping("/{gameId}/messages/{msgId}/report")
    public ResponseEntity<ApiResponse<Void>> report(@PathVariable String gameId,
            @PathVariable Long msgId,
            @AuthenticationPrincipal Long userAccountId) {
        reportService.report(gameId, msgId, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
