package com.skhynix.user.block.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.block.dto.BlockRequest;
import com.skhynix.user.block.dto.BlockResponse;
import com.skhynix.user.block.service.UserBlockService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/users/me/blocks
// /users/** 는 anyRequest().authenticated() 에 자연히 걸린다 — SecurityConfig 에 permitAll 줄을
// 추가하지 말 것(/games/support 선례). 차단 주체·조회 주체는 언제나 토큰 principal 본인이라 경로·본문
// 어디에도 대상 계정 식별자를 받지 않는다.
@RestController
@RequiredArgsConstructor
@RequestMapping("/users/me/blocks")
public class UserBlockController {

    private final UserBlockService userBlockService;

    // 이미 차단한 대상 재요청도 200 이다(USER-BLK-6/7/9, 멱등) — 201 로 고정하지 않는다(승인된 계약).
    @PostMapping
    public ResponseEntity<ApiResponse<BlockResponse>> block(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody BlockRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                userBlockService.block(userAccountId, request.targetNickname())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BlockResponse>>> getMyBlocks(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(userBlockService.getMyBlocks(userAccountId)));
    }
}
