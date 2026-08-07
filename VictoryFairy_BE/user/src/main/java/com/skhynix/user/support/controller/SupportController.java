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

/**
 * 응원 구단·선수 선택 엔드포인트({@code /api/member/support/**}).
 * 요구사항: {@code docs/requirements/user/support-selection.md}.
 *
 * <p><b>{@code SecurityConfig} 를 수정하지 말 것</b> — 세 경로 모두 {@code permitAll} 목록에 없어
 * {@code anyRequest().authenticated()} 에 이미 걸린다. 실수로 열면 {@code SupportControllerTest} 의
 * 401 테스트가 깨진다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/support/**
@RequestMapping("/support")
public class SupportController {

    private final SupportService supportService;

    /**
     * 응원 구단 선택·변경(최초 선택·변경·재선택 공통 경로). {@code POST} 인 이유: 기존 상태를 보고
     * 생성/재활성을 고르는 동작이라 "이 자원을 대체한다"는 {@code PUT} 의미와 어긋난다.
     */
    @PostMapping("/team")
    public ResponseEntity<ApiResponse<TeamResponse>> selectTeam(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportTeamRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.selectTeam(userAccountId, request.teamId())));
    }

    /**
     * 응원 선수 추가. 전체 교체가 아니라 기존 응원에 얹는다 — 요청에 없는 선수는 취소되지 않는다.
     * 응답은 이번에 추가한 선수만이 아니라 <b>현재 응원 중인 선수 전체</b>다.
     */
    @PostMapping("/players")
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> addPlayers(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportPlayersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.addPlayers(userAccountId, request.playerIds())));
    }

    /**
     * 응원 선수 취소. {@code PUT} 인 이유: 행을 지우지 않고 {@code oppose} 컬럼에 시각을 채우는 멱등한
     * 상태 전이라서다(DELETE 본문은 규격상 권장되지 않고 중간 장비가 버릴 수 있어 회피).
     */
    @PutMapping("/players/oppose")
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> opposePlayers(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportPlayersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.opposePlayers(userAccountId, request.playerIds())));
    }
}
