package com.skhynix.user.oauth.store;

import com.skhynix.domain.user.entity.OauthProvider;

/**
 * 1회용 티켓의 내용물 — 인가코드가 1회용이라 "닉네임을 붙여 다시 호출"이 불가능해서, 그 인가코드의
 * 자리를 대신하는 값이다.
 *
 * <p>provider access·refresh 토큰은 담지 않는다. 신원 확인 1회용이라 교환 직후 버린다.
 *
 * @param email         {@link OauthTicketType#INPUT} 이면 {@code null}(사용자가 아직 안 적었다)
 * @param emailVerified {@code email} 의 검증 상태. 소셜 신규 가입 시 그대로 계정에 적힌다
 */
public record OauthTicket(OauthTicketType type, OauthProvider provider, String providerUserId,
                          String email, boolean emailVerified) {

    public static OauthTicket signup(OauthProvider provider, String providerUserId, String email,
            boolean emailVerified) {
        return new OauthTicket(OauthTicketType.SIGNUP, provider, providerUserId, email, emailVerified);
    }

    /** provider 가 준 이메일이 실린다 — 이 이메일로만 인증번호가 나간다. */
    public static OauthTicket link(OauthProvider provider, String providerUserId, String email) {
        return new OauthTicket(OauthTicketType.LINK, provider, providerUserId, email, false);
    }

    public static OauthTicket input(OauthProvider provider, String providerUserId) {
        return new OauthTicket(OauthTicketType.INPUT, provider, providerUserId, null, false);
    }
}
