package com.skhynix.user.account.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.account.dto.NicknameUpdateRequest;
import com.skhynix.user.account.dto.PasswordUpdateRequest;
import com.skhynix.user.account.dto.UserAccountResponse;
import com.skhynix.user.account.service.UserAccountService;
import com.skhynix.user.account.service.UserProfileEditService;
import com.skhynix.user.account.service.UserProfileService;
import com.skhynix.user.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final UserProfileEditService userProfileEditService;

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

    // 대상 계정은 언제나 토큰 주체 본인이라 경로·본문으로 식별자를 받지 않는다.
    // 닉네임은 세션에 영향이 없어 돌려줄 것이 없다(204) — 최신 프로필이 필요하면 GET /users/me 다.
    @PatchMapping("/me/nickname")
    public ResponseEntity<Void> updateNickname(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody NicknameUpdateRequest request) {
        userProfileEditService.updateNickname(userAccountId, request.nickname());
        return ResponseEntity.noContent().build();
    }

    // 닉네임과 달리 200 + 토큰 쌍인 이유: 이 경로가 기존 refresh 토큰을 전부 만료시키므로 그 자리에서
    // 대체물을 주지 않으면 본인이 재로그인해야 한다. 응답 타입은 로그인·재발급과 같은 TokenResponse.
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<TokenResponse>> updatePassword(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody PasswordUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(userProfileEditService.updatePassword(
                userAccountId, request.currentPassword(), request.newPassword())));
    }
}
