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
     * 해당 날짜의 경기 목록(경기 시각 오름차순). 경기가 없으면 200 + 빈 배열.
     *
     * <p>날짜는 경로변수가 아니라 <b>쿼리 파라미터</b>로 받는다:
     * {@code GET /api/member/games?date=2026-08-01}. 형식은 ISO {@code yyyy-MM-dd} 고정이다.
     *
     * <p><b>{@code date} 는 선택이다 — 생략하면 한국({@code Asia/Seoul}) 기준 "오늘"의 경기를 반환한다.</b>
     * 다만 형식이 어긋난 값({@code date=20260801})은 여전히 컨트롤러 진입 전 타입 변환에서 400 이다
     * ("없으면 오늘"이지 "이상하면 오늘"이 아니다 — 오타를 오늘로 흡수하면 사용자가 잘못된 날짜를 봤다는 사실을
     * 알 수 없다).
     *
     * <p>기본값(오늘) 해석은 컨트롤러가 하지 않고 <b>서비스로 그대로 {@code null} 을 넘긴다</b>. "오늘"은
     * 시간대 규칙이 걸린 도메인 판단이고, 그 판단에 필요한 {@code Clock} 은 서비스가 들고 있기 때문이다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GameResponse>>> getGames(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getGames(date)));
    }
}
