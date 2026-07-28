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
 * 요구사항: {@code docs/requirements/user/support-selection.md}(USER-SP-1 ~ 29).
 *
 * <p><b>{@code SecurityConfig} 를 수정하지 않는다</b> — 세 경로 모두 {@code /auth/**} 밖이고
 * {@code permitAll} 목록에 없어 {@code anyRequest().authenticated()} 에 그대로 걸린다
 * ({@code UserAccountController} 와 같은 방식). {@code /teams}·{@code /players} 는 한 줄을 추가하는 것이
 * 정답이었지만 여기서는 <b>아무것도 추가하지 않는 것이 정답</b>이고, 실수로 열면 USER-SP-1 이 깨진다.
 *
 * <p>대상 계정은 본문·경로가 아니라 access 토큰에서만 정해진다. principal 은
 * {@code JwtAuthenticationFilter} 가 토큰 subject(uid)를 해석해 넣은 내부 PK({@code Long})이며, 탈퇴 계정은
 * 그 해석 단계({@code findActiveIdByUid}) 에서 걸러져 이 컨트롤러에 닿지 않는다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/support/**
@RequestMapping("/support")
public class SupportController {

    private final SupportService supportService;

    /**
     * 응원 구단 선택·변경. 최초 선택·변경·재선택을 한 경로가 모두 처리한다.
     *
     * <p>{@code POST} 인 이유: 서버의 기존 상태를 보고 생성 또는 재활성을 고르는 동작이라 "이 자원을 이
     * 표현으로 대체한다"는 {@code PUT} 의미와 어긋난다. 구단을 <b>해제</b>하는 경로는 없다 — 구단은
     * 필수라서 응원하지 않는 상태가 계약상 존재하지 않고, 변경만 있다.
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
     * 응원 선수 취소.
     *
     * <p>{@code PUT} 인 이유: 행을 지우는 것이 아니라 {@code oppose} 컬럼에 시각을 채우는 상태 전이이고,
     * 이미 취소된 행에 {@code oppose()} 가 no-op 이라 두 번 보내도 결과가 같다. {@code DELETE} 를 쓰면
     * 본문에 리스트를 싣기 껄끄러워 추가 API 와 비대칭이 된다(DELETE 본문은 규격상 권장되지 않고 중간
     * 장비가 버릴 수 있다).
     */
    @PutMapping("/players/oppose")
    public ResponseEntity<ApiResponse<List<PlayerResponse>>> opposePlayers(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody SupportPlayersRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                supportService.opposePlayers(userAccountId, request.playerIds())));
    }
}
