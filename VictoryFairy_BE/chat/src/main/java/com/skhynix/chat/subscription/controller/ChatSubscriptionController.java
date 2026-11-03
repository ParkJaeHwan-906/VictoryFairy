package com.skhynix.chat.subscription.controller;

import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.subscription.service.ChatSubscriptionService;
import com.skhynix.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 표준 EventSource 는 Authorization 헤더를 못 실어 401 이다 — fetch 기반 폴리필로 구독해야 한다.
@RestController
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/rooms")
public class ChatSubscriptionController {

    private final ChatSubscriptionService subscriptionService;

    @GetMapping(value = "/{gameId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String gameId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal Long userAccountId) {
        return subscriptionService.subscribe(gameId, userAccountId, lastEventId);
    }

    @DeleteMapping("/{gameId}/subscribe")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(@PathVariable String gameId,
            @AuthenticationPrincipal Long userAccountId) {
        subscriptionService.unsubscribe(gameId, userAccountId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
