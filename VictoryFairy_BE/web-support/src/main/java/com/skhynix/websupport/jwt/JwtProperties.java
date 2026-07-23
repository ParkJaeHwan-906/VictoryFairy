package com.skhynix.websupport.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jwt.* 설정 값 바인딩.
 *
 * <pre>
 * jwt:
 *   secret: ...           # HS256 서명 키 (32바이트 이상)
 *   access-token-validity: 1800000      # access 토큰 만료(ms)
 *   refresh-token-validity: 1209600000  # refresh 토큰 만료(ms)
 * </pre>
 */
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String secret;
    private long accessTokenValidity;
    private long refreshTokenValidity;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTokenValidity() {
        return accessTokenValidity;
    }

    public void setAccessTokenValidity(long accessTokenValidity) {
        this.accessTokenValidity = accessTokenValidity;
    }

    public long getRefreshTokenValidity() {
        return refreshTokenValidity;
    }

    public void setRefreshTokenValidity(long refreshTokenValidity) {
        this.refreshTokenValidity = refreshTokenValidity;
    }
}
