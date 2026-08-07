package com.skhynix.user.player.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.player.service.PlayerService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/players
@RequestMapping("/players")
public class PlayerController {

    private final PlayerService playerService;

    /**
     * 선수 목록(name 오름차순, 없으면 200 + 빈 배열). 구단 조건은 요청의 {@code teamId} 가 아니라
     * <b>적용 구단</b>이며, 활성 응원 구단이 있으면 그쪽이 {@code teamId} 를 오버라이딩한다
     * ({@code docs/requirements/user/player-lookup-team-fallback.md}). 결정은
     * {@code PlayerService} 가 하고 여기서는 판단하지 않는다.
     *
     * <p>{@code name} 은 적용 구단과 AND 결합(부분 일치)한다. {@code teamId} 가 숫자가 아니면 컨트롤러
     * 진입 전 타입 변환에서 400 — 오버라이딩 판단에 닿지도 못하며,
     * {@code GlobalExceptionHandler} 를 안 타 {@code ApiResponse} 래퍼가 아니다.
     *
     * <p>이 경로는 {@code permitAll} 이라 비인증도 401 이 아닌 200 이다 — 그때
     * {@code userAccountId} 는 {@code null} 이고, 무효 토큰·탈퇴 계정도 필터가 principal 을 비워 두어
     * 헤더 없음과 구분되지 않는다.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> getPlayers(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String name) {
        return ResponseEntity.ok(ApiResponse.ok(playerService.getPlayers(userAccountId, teamId, name)));
    }
}
