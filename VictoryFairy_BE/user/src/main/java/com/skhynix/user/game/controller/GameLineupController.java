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

/**
 * 경기별 선발 라인업 조회 엔드포인트. 경기 목록과 같은 공개 참조 데이터라 {@code GET} 만
 * {@code SecurityConfig} 에서 {@code permitAll} 로 열려 있다(비-GET 은 401).
 *
 * <p>{@code /games} 매처는 정확 경로 매칭이라 이 하위 경로를 커버하지 않는다 — 경로가 {@code /games}
 * 아래라는 이유로 {@code SecurityConfig} 한 줄을 빠뜨리면 전 요청이 401 이 된다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/games/lineup
@RequestMapping("/games/lineup")
public class GameLineupController {

    private final GameLineupService gameLineupService;

    /**
     * 해당 경기의 선발 라인업(팀 그룹 배열, {@code teamId} 오름차순). 라인업이 아직 공시되지 않았으면
     * 빈 배열이고, 없는 경기면 404 다.
     *
     * <p>{@code gameId} 는 경로 변수가 아니라 쿼리 파라미터로 받는다 — 경로 변수로 두면 파라미터 누락이
     * "경로 불일치 404" 로 성질이 바뀌어 없는 경기(404)와 뭉개진다.
     *
     * <p>빈/공백 값에 {@code @Validated}+{@code @NotBlank} 를 걸지 않는다: {@code GlobalExceptionHandler}
     * 가 {@code ConstraintViolationException} 을 처리하지 않아 400 이 아니라 500 이 된다. 빈 문자열은
     * 일치하는 {@code naver_game_id} 가 없어 서비스에서 자연히 404 로 흡수된다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GameLineupResponse>>> getLineup(
            @RequestParam String gameId) {
        return ResponseEntity.ok(ApiResponse.ok(gameLineupService.getLineup(gameId)));
    }
}
