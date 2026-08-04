package com.skhynix.user.player.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.player.service.PlayerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 선수 목록 조회 엔드포인트. 구단 목록과 같은 참조 데이터라 {@code GET} 만 {@code SecurityConfig} 에서
 * {@code permitAll} 로 열려 있다(비-GET 은 401).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/players
@RequestMapping("/players")
public class PlayerController {

    private final PlayerService playerService;

    /**
     * 선수 목록(name 오름차순, 없으면 200 + 빈 배열). {@code teamId}·{@code name} 은 AND 결합(부분
     * 일치), 둘 다 생략하면 전체다. {@code teamId} 가 숫자가 아니면 컨트롤러 진입 전 타입 변환에서
     * 400 — {@code GlobalExceptionHandler} 를 안 타 {@code ApiResponse} 래퍼가 아니다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> getPlayers(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String name) {
        return ResponseEntity.ok(ApiResponse.ok(playerService.getPlayers(teamId, name)));
    }
}
