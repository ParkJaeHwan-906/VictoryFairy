package com.skhynix.user.game.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.game.dto.GameLineupResponse;
import com.skhynix.user.game.service.GameLineupService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/games/lineup
@RequestMapping("/games/lineup")
public class GameLineupController {

    private final GameLineupService gameLineupService;

    // gameId 에 @Validated+@NotBlank 를 걸지 말 것 — GlobalExceptionHandler 가 ConstraintViolationException 을
    // 처리하지 않아 400 이 아니라 500 이 된다(빈 문자열은 서비스에서 404 로 흡수된다).
    @GetMapping
    public ResponseEntity<ApiResponse<List<GameLineupResponse>>> getLineup(
            @RequestParam String gameId) {
        return ResponseEntity.ok(ApiResponse.ok(gameLineupService.getLineup(gameId)));
    }
}
