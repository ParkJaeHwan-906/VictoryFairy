package com.skhynix.user.auth.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.auth.dto.EmailSendCodeRequest;
import com.skhynix.user.auth.dto.EmailVerifyRequest;
import com.skhynix.user.auth.dto.LoginRequest;
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
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    /**
     * 회원가입용 이메일 인증번호 발송. 형식(400)은 Bean Validation이, 가입 이력(409)·쿨다운(429)은
     * 서비스가 판정한다. dev/test는 실제 발송 없이 mock(로그)으로 대체된다.
     */
    @PostMapping("/email/send-code")
    public ResponseEntity<ApiResponse<Void>> sendEmailCode(@Valid @RequestBody EmailSendCodeRequest request) {
        emailVerificationService.sendCode(request.email());
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    /**
     * 인증번호 검증. 성공 시 인증완료 상태를 저장하고 200을 반환한다. 불일치/만료·무효/시도 초과는
     * BusinessException(400)으로 GlobalExceptionHandler가 처리한다.
     */
    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Void>> verifyEmailCode(@Valid @RequestBody EmailVerifyRequest request) {
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    /**
     * 비밀번호 정책 사전 검사. 입력창에서 타이핑마다 호출되는 용도라 DB를 보지 않는다.
     *
     * <p>비밀번호가 URL·접근 로그에 남지 않도록 GET이 아닌 POST로 받는다. "정책 위반"은 검사가
     * 정상 수행된 결과이므로 400이 아니라 항상 200 + {@code valid:false}로 응답한다. 같은 이유로
     * 요청 DTO에 {@code @Valid}를 걸지 않는다(임의 문자열을 그대로 판정해야 한다).
     *
     * <p>판정 규칙은 {@link PasswordPolicy}가 단일 출처로 갖고 있다. 상태도 협력 객체도 없는 순수
     * 유틸이라 서비스 계층을 거치지 않고 직접 호출한다.
     */
    @PostMapping("/password/validate")
    public ResponseEntity<ApiResponse<PasswordValidationResponse>> validatePassword(
            @RequestBody PasswordValidationRequest request) {
        PasswordValidationResponse result = PasswordPolicy.findViolation(request.password())
                .map(PasswordValidationResponse::violated)
                .orElseGet(PasswordValidationResponse::passed);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/signup")
    public ResponseEntity<Boolean> signup(@Valid @RequestBody SignupRequest request) {
        Long userAccountId = authService.signup(request);
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
