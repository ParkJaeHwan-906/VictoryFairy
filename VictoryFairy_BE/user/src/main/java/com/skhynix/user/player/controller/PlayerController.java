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

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/players
@RequestMapping("/players")
public class PlayerController {

    private final PlayerService playerService;

    // permitAll 경로라 비인증이면 userAccountId 가 null 로 들어온다(무효 토큰·탈퇴 계정도 동일).
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> getPlayers(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String name) {
        return ResponseEntity.ok(ApiResponse.ok(playerService.getPlayers(userAccountId, teamId, name)));
    }
}
