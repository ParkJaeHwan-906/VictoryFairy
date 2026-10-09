package com.skhynix.chat.like.controller;

import com.skhynix.chat.like.service.ChatLikeService;
import com.skhynix.chat.shared.ChatRoles;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좋아요(CHAT-LK-1~3·7·48). 성공은 202 본문 없음 — 다른 chat 엔드포인트와 달리 {@code ApiResponse} 래퍼가 없다.
 *
 * <p>⚠ 클래스에 {@code @Validated} 를 붙이지 말 것. 붙이면 경로 변수 검증이 스프링 내장 검증 대신 AOP 로 돌아
 * {@code ConstraintViolationException} 이 공유 핸들러의 catch-all 에 걸려 400 이 아니라 500 이 된다.
 * 지금 형태(파라미터에 제약만)면 내장 검증의 {@code HandlerMethodValidationException} 이 래퍼 붙은 400 으로 나간다.
 *
 * <p>본문을 받지 않는다({@code @RequestBody} 없음). 그래서 본문·Content-Type 이 무엇이든 415·400 이 나지 않는다(CHAT-LK-7).
 */
@RestController
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@RequestMapping("/rooms")
public class ChatLikeController {

    private final ChatLikeService likeService;

    @PostMapping("/{gameId}/likes")
    public ResponseEntity<Void> like(
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9]{1,20}$", message = "gameId는 1~20자의 영문·숫자여야 합니다.")
            String gameId,
            @AuthenticationPrincipal Long userAccountId) {
        likeService.like(gameId, userAccountId);
        return ResponseEntity.accepted().build();
    }
}
