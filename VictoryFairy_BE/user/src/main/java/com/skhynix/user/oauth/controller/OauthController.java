package com.skhynix.user.oauth.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.auth.dto.TokenResponse;
import com.skhynix.user.oauth.dto.OauthAuthResponse;
import com.skhynix.user.oauth.dto.OauthLinkSendCodeRequest;
import com.skhynix.user.oauth.dto.OauthLinkVerifyRequest;
import com.skhynix.user.oauth.dto.OauthLoginRequest;
import com.skhynix.user.oauth.dto.OauthSignupRequest;
import com.skhynix.user.oauth.service.OauthAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인 4개 경로.
 *
 * <p>{@code /auth/**} 아래라 {@code SecurityConfig} 수정 없이 permitAll 로 열린다 — 인증 없이 처리돼야
 * 하는 경로이므로 그게 정답이다. ⚠ 반대로 나중에 <b>인증이 필요한</b> 소셜 API(연동 목록·연동 해제)를
 * 붙일 때 이 아래에 두면 인증이 걸리지 않는다.
 *
 * <p>접두사 {@code /api} 는 {@code server.servlet.context-path} 가 붙인다 → 실제 노출 경로는
 * {@code /api/auth/oauth/**}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/oauth")
public class OauthController {

    private final OauthAuthService oauthAuthService;

    /**
     * ⚠ 이 응답의 {@code status} 는 넷 다 <b>200</b> 이다. 가입·이메일 인증·이메일 입력이 필요한 상태를
     * 4xx 로 바꾸면 프론트의 공통 인터셉터가 오류로 삼켜 다음 단계 화면이 열리지 않는다.
     *
     * <p>{@code {provider}} 는 한 세그먼트라 {@code /link/**} 와 겹치지 않고, {@code /signup} 은
     * 리터럴 패턴이 템플릿보다 우선해 아래 메서드로 간다. ⚠ 이 경로 바로 아래에 한 세그먼트짜리
     * 리터럴 경로를 새로 만들면 그 이름의 provider 를 영원히 못 쓰게 된다.
     */
    @PostMapping("/{provider}")
    public ResponseEntity<OauthAuthResponse> authenticate(@PathVariable String provider,
            @Valid @RequestBody OauthLoginRequest request) {
        return ResponseEntity.ok(oauthAuthService.authenticate(provider, request));
    }

    /**
     * 링크 티켓·입력 티켓이 <b>같은 경로</b>를 쓴다(엔드포인트를 종류만큼 늘리지 않는다). 본문 이메일을
     * 받아들이는 것은 입력 티켓뿐이며, 그 판정은 서비스가 티켓 종류로 한다.
     *
     * <p>응답 형태는 자체 인증번호 발송({@code /auth/email/send-code})과 같은 {@code ApiResponse} 다 —
     * 같은 일을 하는 경로라 클라이언트가 다르게 다룰 이유가 없다. 다만 <b>가입된 이메일에 409 를 주는
     * 그쪽 계약은 따라오지 않는다</b>(여기서 갈리면 계정 열거가 된다).
     */
    @PostMapping("/link/send-code")
    public ResponseEntity<ApiResponse<Void>> sendLinkCode(
            @Valid @RequestBody OauthLinkSendCodeRequest request) {
        oauthAuthService.sendLinkCode(request);
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    /** 인증 통과 후의 응답은 최초 인증과 같은 형태다 — 계정이 있으면 LOGIN, 없으면 SIGNUP_REQUIRED. */
    @PostMapping("/link/verify")
    public ResponseEntity<OauthAuthResponse> verifyLinkCode(
            @Valid @RequestBody OauthLinkVerifyRequest request) {
        return ResponseEntity.ok(oauthAuthService.verifyLinkCode(request));
    }

    // 자체 가입이 Boolean 을 주는 것과 달리 토큰 쌍을 준다 — 이 경로는 이미 소셜 인증을 통과한
    // 상태라 가입 직후 곧바로 로그인 상태가 되는 것이 맞다.
    @PostMapping("/signup")
    public ResponseEntity<TokenResponse> signup(@Valid @RequestBody OauthSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(oauthAuthService.signup(request));
    }
}
