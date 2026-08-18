package com.skhynix.user.support.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.support.dto.SupportPlayersRequest;
import com.skhynix.user.support.dto.SupportTeamRequest;
import com.skhynix.user.support.service.SupportService;
import com.skhynix.user.team.dto.TeamResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// SecurityConfig 를 수정하지 말 것 — 세 경로 모두 permitAll 목록에 없어 이미 인증이 걸린다.
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/support/**
@RequestMapping("/support")
public class SupportController {

    private final SupportService supportService;

    @PostMapping("/team")
    public ResponseEntity<ApiResponse<TeamResponse>> selectTeam(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportTeamRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.selectTeam(userAccountId, request.teamId())));
    }

    @PostMapping("/players")
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> addPlayers(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportPlayersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.addPlayers(userAccountId, request.playerIds())));
    }

    // DELETE 가 아닌 이유: DELETE 본문은 규격상 권장되지 않고 중간 장비가 버릴 수 있다.
    @PutMapping("/players/oppose")
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> opposePlayers(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportPlayersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.opposePlayers(userAccountId, request.playerIds())));
    }
}
