package com.skhynix.user.auth.controller;

import com.skhynix.user.auth.dto.LoginRequest;
import com.skhynix.user.auth.dto.SignupRequest;
import com.skhynix.user.auth.dto.TokenRequest;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.auth.service.AuthService;
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
