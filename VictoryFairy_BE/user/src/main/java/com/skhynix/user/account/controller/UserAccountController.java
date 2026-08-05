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

/**
 * 계정 자원({@code /api/member/users}). {@code /api/member/auth/**} 는 전부 {@code permitAll} 이라
 * 탈퇴를 그쪽에 두면 인증이 걸리지 않아 이 경로에 둔다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api/member 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/member/users/**
@RequestMapping("/users")
public class UserAccountController {

    private final UserAccountService userAccountService;
    private final UserProfileService userProfileService;

    /**
     * 회원 탈퇴. 요청 본문 없음(비밀번호 재확인 없음). 대상 계정은 access 토큰의 principal 로만 정해진다.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userAccountId) {
        userAccountService.withdraw(userAccountId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 내 요약 프로필 조회(닉네임 · 응원 구단 · 보유 포인트 · 누적 획득 점수).
     * 요구사항: {@code docs/requirements/user/me-profile.md}.
     *
     * <p>탈퇴와 마찬가지로 대상 계정은 access 토큰에서만 정해진다.
     *
     * <p>엔티티가 아니라 {@link UserAccountResponse} 를 반환한다 — {@code UserAccount} 를 그대로 실으면
     * {@code password} 해시·{@code uid} 가 함께 나간다.
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserAccountResponse>> getMyProfile(
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(userProfileService.getMyProfile(userAccountId)));
    }
}
