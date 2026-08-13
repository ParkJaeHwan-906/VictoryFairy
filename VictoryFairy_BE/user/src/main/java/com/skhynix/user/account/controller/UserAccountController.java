package com.skhynix.user.account.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// /auth/** 는 전부 permitAll 이라 탈퇴를 그쪽에 두면 인증이 걸리지 않는다 — 그래서 이 경로다.
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/users/**
@RequestMapping("/users")
public class UserAccountController {

    private final UserAccountService userAccountService;
    private final UserProfileService userProfileService;

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userAccountId) {
        userAccountService.withdraw(userAccountId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserAccountResponse>> getMyProfile(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(userProfileService.getMyProfile(userAccountId)));
    }
}
