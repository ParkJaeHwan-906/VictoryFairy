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

/**
 * 날짜별 경기 목록 조회 엔드포인트. 구단·선수 목록과 같은 공개 참조 데이터라 {@code GET} 만
 * {@code SecurityConfig} 에서 {@code permitAll} 로 열려 있다(비-GET 은 401).
 *
 * <p>단 하위 경로 {@code /games/support} 는 예외로 인증이 필요하다 — 같은 접두사 아래에서 인증 정책이
 * 갈리므로 매처를 손댈 때 주의할 것(아래 해당 메서드 주석 참고).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/games
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

    /**
     * 위 목록을 요청자의 활성 응원 구단(홈·원정 무관) 경기로 좁힌 것. 응답 형식·날짜 규칙은 동일하고
     * <b>인증만 필수</b>다.
     *
     * <p>{@code SecurityConfig} 는 손대지 않는다 — {@code /games} 의 {@code permitAll} 매처가 정확
     * 매칭이라 하위 경로인 여기는 {@code anyRequest().authenticated()} 에 그대로 걸린다. 실수로
     * {@code /games/support} 를 열면 미인증 401 계약이 통째로 깨진다.
     */
    @GetMapping("/support")
    public ResponseEntity<ApiResponse<List<GameResponse>>> getSupportTeamGames(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.ok(gameService.getSupportTeamGames(userAccountId, date)));
    }
}
