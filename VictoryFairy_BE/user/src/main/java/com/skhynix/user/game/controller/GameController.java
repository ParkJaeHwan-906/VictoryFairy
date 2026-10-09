package com.skhynix.user.game.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.service.GameService;
import com.skhynix.user.game.service.GameSubscriptionService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/games
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;

    private final GameSubscriptionService gameSubscriptionService;

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

    // GET /games 의 SSE 판. /games 매처가 정확 매칭이라 이 경로는 SecurityConfig 에 별도 permitAll 줄이 있다
    // (/games/lineup 과 같은 사정) — 그 줄을 빼면 공개 조회가 401 이 된다.
    // 응답은 ApiResponse 래퍼가 아니라 이벤트 스트림이다: 연결 직후 snapshot(현재 목록), 이후 game-update.
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return gameSubscriptionService.subscribe(date);
    }

    // GET /games/support 의 SSE 판. /games/support 와 같은 이유로 매처 없이 자연히 인증이 걸린다.
    @GetMapping(value = "/support/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeSupportTeamGames(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return gameSubscriptionService.subscribeSupportTeam(userAccountId, date);
    }
}
