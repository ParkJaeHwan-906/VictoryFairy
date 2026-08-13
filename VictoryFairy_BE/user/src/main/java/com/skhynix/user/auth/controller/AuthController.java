package com.skhynix.user.auth.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.auth.dto.EmailSendCodeRequest;
import com.skhynix.user.auth.dto.EmailVerifyRequest;
import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.NicknameValidationRequest;
import com.skhynix.user.auth.dto.NicknameValidationResponse;
import com.skhynix.user.auth.dto.PasswordValidationRequest;
import com.skhynix.user.auth.dto.PasswordValidationResponse;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.policy.PasswordPolicy;
import com.skhynix.user.auth.service.AuthService;
import com.skhynix.user.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/auth/**
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/email/send-code")
    public ResponseEntity<ApiResponse<Void>> sendEmailCode(@Valid @RequestBody EmailSendCodeRequest request) {
        emailVerificationService.sendCode(request.email());
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Void>> verifyEmailCode(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    // 비밀번호가 본문에 실려 로그 노출을 피하려고 GET 이 아니라 POST 다.
    @PostMapping("/password/validate")
    public ResponseEntity<ApiResponse<PasswordValidationResponse>> validatePassword(
            @RequestBody PasswordValidationRequest request) {
        PasswordValidationResponse result = PasswordPolicy.findViolation(request.password())
                .map(PasswordValidationResponse::violated)
                .orElseGet(PasswordValidationResponse::passed);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/nickname/validate")
    public ResponseEntity<ApiResponse<NicknameValidationResponse>> validateNickname(
            @RequestBody NicknameValidationRequest request) {
        NicknameValidationResponse result = authService.validateNickname(request.nickname());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/nickname/duplicate")
    public ResponseEntity<ApiResponse<NicknameValidationResponse>> checkNicknameDuplicate(
            @RequestBody NicknameValidationRequest request) {
        NicknameValidationResponse result = authService.checkNicknameDuplicate(request.nickname());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/signup")
    public ResponseEntity<Boolean> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(true);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody TokenRequest request) {
        return ResponseEntity.ok(authService.reissue(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody TokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
