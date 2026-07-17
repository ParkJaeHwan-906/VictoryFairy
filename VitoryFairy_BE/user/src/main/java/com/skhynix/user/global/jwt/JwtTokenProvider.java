package com.skhynix.user.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTokenValidity;
    private final long refreshTokenValidity;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidity = properties.getAccessTokenValidity();
        this.refreshTokenValidity = properties.getRefreshTokenValidity();
    }

    public String createAccessToken(String uid) {
        return createToken(uid, TYPE_ACCESS, accessTokenValidity);
    }

    public String createRefreshToken(String uid) {
        return createToken(uid, TYPE_REFRESH, refreshTokenValidity);
    }

    /**
     * subject에는 외부 노출용 {@code uid}만 담는다. JWT payload는 서명될 뿐 암호화되지 않아
     * base64 디코드만으로 누구나 읽을 수 있으므로, 내부 PK({@code id})는 claim에 절대 넣지 않는다.
     */
    private String createToken(String uid, String type, long validityMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(uid)
                .claim(CLAIM_TYPE, type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + validityMillis))
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUid(String token) {
        return parse(token).getSubject();
    }

    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parse(token).get(CLAIM_TYPE, String.class));
    }

    /**
     * 만료된 토큰이어도 ExpiredJwtException에서 만료 시각을 꺼낼 수 있어 그대로 던지지 않고 변환한다.
     */
    public LocalDateTime getExpiration(String token) {
        Date expiration;
        try {
            expiration = parse(token).getExpiration();
        } catch (ExpiredJwtException e) {
            expiration = e.getClaims().getExpiration();
        }
        return expiration.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
