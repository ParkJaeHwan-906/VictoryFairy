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
import com.skhynix.user.profileimage.dto.ProfileImageResponse;
import com.skhynix.user.profileimage.service.TempProfileImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/auth/**
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final TempProfileImageService tempProfileImageService;

    /**
     * 가입 전 프로필 이미지 업로드 — 이 저장소에서 <b>인증 없이 쓰기가 되는 유일한 경로</b>다.
     *
     * <p>여기에 둔 것이 곧 보안 설정이다: {@code /auth/**} 는 이미 전부 permitAll 이라 SecurityConfig
     * 를 건드리지 않아도 열린다. ⚠ 경로를 {@code /users/**} 아래로 옮기면 401 이 되고, 거기에 여는
     * 줄을 추가하는 순간 기존 공개 줄(전부 GET 한정)의 성격이 깨진다.
     *
     * <p>유효한 access 토큰이 함께 와도 동작이 달라지지 않는다 — 저장 위치는 언제나 {@code temp/}
     * 이고 계정 컬럼은 갱신되지 않는다(토큰이 있으면 결과가 달라지는 {@code GET /players} 와 반대다).
     *
     * <p>{@code image} 는 {@code @RequestPart(required = false)} 다. 파트가 없을 때와 이름이 다를 때를
     * 스프링이 던지는 예외 대신 같은 400 으로 흡수하기 위해서다. {@code appId} 는 파일이 아닌 파트라
     * {@code @RequestParam} 으로 받는다(멀티파트의 일반 파트는 요청 파라미터로 노출된다).
     */
    @PostMapping(path = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProfileImageResponse>> uploadTempProfileImage(
            @RequestParam(name = "appId", required = false) String appId,
            @RequestPart(name = "image", required = false) MultipartFile image) {
        return ResponseEntity.ok(ApiResponse.ok(tempProfileImageService.upload(appId, image)));
    }

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
