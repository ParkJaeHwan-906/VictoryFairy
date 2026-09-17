package com.skhynix.user.oauth.dto;

import com.skhynix.user.auth.dto.TokenResponse;

/**
 * 소셜 인증 경로(최초 인증 · 인증번호 확인)의 <b>단일 응답 형태</b>. 다섯 키가 언제나 그대로 나가고
 * 쓰이지 않는 자리는 {@code null} 이다 — 단계마다 키 구성이 달라지면 클라이언트가 응답 모양으로
 * 분기하게 되고, 그러면 {@code status} 계약이 있으나 마나가 된다.
 *
 * <p>provider access·refresh 토큰은 어느 키에도 실리지 않는다.
 */
public record OauthAuthResponse(OauthAuthStatus status, String accessToken, String refreshToken,
                                String ticket, String email) {

    public static OauthAuthResponse login(TokenResponse tokens) {
        return new OauthAuthResponse(OauthAuthStatus.LOGIN, tokens.accessToken(),
                tokens.refreshToken(), null, null);
    }

    public static OauthAuthResponse signupRequired(String ticket, String email) {
        return new OauthAuthResponse(OauthAuthStatus.SIGNUP_REQUIRED, null, null, ticket, email);
    }

    /**
     * {@code email} 을 실어 보내는 것은 계정 존재를 흘리는 것이 아니다 — 이 값은 방금 provider 가
     * 우리에게 준 이메일이라 요청자가 이미 알고 있고, {@code status} 자체가 이미 "그 이메일의 계정이
     * 있다"를 말한다. 인증번호를 어디로 보냈는지 화면에 띄우려면 필요하다.
     */
    public static OauthAuthResponse emailVerificationRequired(String ticket, String email) {
        return new OauthAuthResponse(OauthAuthStatus.EMAIL_VERIFICATION_REQUIRED, null, null,
                ticket, email);
    }

    public static OauthAuthResponse emailInputRequired(String ticket) {
        return new OauthAuthResponse(OauthAuthStatus.EMAIL_INPUT_REQUIRED, null, null, ticket, null);
    }
}
