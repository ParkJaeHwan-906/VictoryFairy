package com.skhynix.user.ranking.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.ranking.dto.BqRankingResponse;
import com.skhynix.user.ranking.service.BqRankingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/rankings/bq/**
// SecurityConfig 에 permitAll 줄을 추가하지 말 것 — anyRequest().authenticated() 에 걸리는 것이 정답이다.
// 구단·대상 계정은 토큰 주체로만 정한다(경로·쿼리 파라미터 없음).
@RequestMapping("/rankings/bq")
public class BqRankingController {

    private final BqRankingService bqRankingService;

    @GetMapping("/top")
    public ResponseEntity<ApiResponse<List<BqRankingResponse>>> getTopRanking(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(bqRankingService.getTopRanking(userAccountId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BqRankingResponse>>> getRanking(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(bqRankingService.getRanking(userAccountId)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<BqRankingResponse>> getMyRanking(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(bqRankingService.getMyRanking(userAccountId)));
    }
}
