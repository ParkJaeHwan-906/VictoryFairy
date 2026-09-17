package com.skhynix.user.game.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.service.GameService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/games
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<GameResponse>>> getGames(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getGames(date)));
    }

    // /games 의 permitAll 매처가 정확 매칭이라 하위 경로인 여기는 인증이 걸린다. 매처를 넓히면 401 계약이 깨진다.
    @GetMapping("/support")
    public ResponseEntity<ApiResponse<List<GameResponse>>> getSupportTeamGames(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getSupportTeamGames(userAccountId, date)));
    }
}
