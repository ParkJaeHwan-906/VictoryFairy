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

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/teams
@RequestMapping("/teams")
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponse>>> getTeams() {
        return ResponseEntity.ok(ApiResponse.ok(teamService.getTeams()));
    }
}
