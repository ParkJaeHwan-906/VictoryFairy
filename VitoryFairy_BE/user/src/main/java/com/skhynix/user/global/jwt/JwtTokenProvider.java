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

    public String createAccessToken(Long userAccountId) {
        return createToken(userAccountId, TYPE_ACCESS, accessTokenValidity);
    }

    public String createRefreshToken(Long userAccountId) {
        return createToken(userAccountId, TYPE_REFRESH, refreshTokenValidity);
    }

    private String createToken(Long userAccountId, String type, long validityMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userAccountId))
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

    public Long getUserAccountId(String token) {
        return Long.valueOf(parse(token).getSubject());
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
