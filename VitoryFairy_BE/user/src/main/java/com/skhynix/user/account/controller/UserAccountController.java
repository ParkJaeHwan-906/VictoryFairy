package com.skhynix.user.account.controller;

import com.skhynix.user.account.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 자원({@code /api/users}). {@code /api/auth/**}가 전부 {@code permitAll}이라 탈퇴를 그쪽에 두면
 * 인증이 걸리지 않으므로, {@code SecurityConfig}의 {@code anyRequest().authenticated()}에 그대로
 * 걸리는 이 경로에 둔다(그래서 SecurityConfig 수정이 필요 없다).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserAccountController {

    private final UserAccountService userAccountService;

    /**
     * 회원 탈퇴. 요청 본문이 없다(비밀번호 재확인 없음).
     *
     * <p>대상 계정은 경로가 아니라 access 토큰에서만 정해진다 — principal은
     * {@code JwtAuthenticationFilter}가 토큰 subject(uid)를 해석해 넣은 내부 PK({@code Long})다.
     * 미인증 요청은 이 메서드에 닿기 전에 엔트리포인트가 401로 응답한다.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userAccountId) {
        userAccountService.withdraw(userAccountId);
        return ResponseEntity.noContent().build();
    }
}
