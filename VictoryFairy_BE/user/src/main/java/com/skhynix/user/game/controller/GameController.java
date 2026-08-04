package com.skhynix.user.game.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.service.GameService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 날짜별 경기 목록 조회 엔드포인트. 구단·선수 목록과 같은 공개 참조 데이터라 {@code GET} 만
 * {@code SecurityConfig} 에서 {@code permitAll} 로 열려 있다(비-GET 은 401).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/games
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;

    /**
     * 해당 날짜의 경기 목록(경기 시각 오름차순, 없으면 200 + 빈 배열). {@code date} 는 쿼리 파라미터
     * (ISO {@code yyyy-MM-dd})로 선택이며, 생략하면 서비스가 한국 기준 오늘로 대체한다. 형식이 어긋난
     * 값은 컨트롤러 진입 전 타입 변환에서 400 — 오타를 오늘로 흡수하지 않는다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GameResponse>>> getGames(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getGames(date)));
    }
}
