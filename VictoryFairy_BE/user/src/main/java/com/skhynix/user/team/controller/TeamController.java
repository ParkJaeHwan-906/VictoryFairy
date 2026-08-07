package com.skhynix.user.team.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.team.dto.TeamResponse;
import com.skhynix.user.team.service.TeamService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구단(팀) 목록 조회 엔드포인트. 회원가입 화면 등 로그인 이전 화면에서도 쓰이므로 {@code GET} 만
 * {@code SecurityConfig} 에서 {@code permitAll} 로 열려 있다(비-GET 은 401).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/teams
@RequestMapping("/teams")
public class TeamController {

    private final TeamService teamService;

    /**
     * 전체 구단 목록(name 오름차순). 페이징 파라미터는 해석하지 않으며 항상 단일 배열로 반환한다.
     * 데이터가 없으면 200 + 빈 배열.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponse>>> getTeams() {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getTeams()));
    }
}
