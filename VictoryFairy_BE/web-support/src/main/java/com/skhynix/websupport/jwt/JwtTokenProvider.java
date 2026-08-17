package com.skhynix.websupport.jwt;

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
     * JWT payload는 서명될 뿐 암호화되지 않아 base64 디코드만으로 누구나 읽을 수 있으므로,
     * 내부 PK({@code id})는 claim에 절대 넣지 않는다.
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

    /**
     * 발급 시각(`iat`)을 epoch 초로 돌려준다 — 계정별 토큰 무효화 기준 시각과 대조하는 값이다.
     * {@code Date}·{@code LocalDateTime}이 아니라 epoch 초인 이유는 비교 상대가 존 개념 없는 값이라
     * 어느 쪽에서도 시간대를 고를 일이 없어야 하기 때문이다(고를 수 있으면 언젠가 서로 다르게 고른다).
     *
     * <p>이 저장소가 발급하는 토큰에는 {@code createToken}이 항상 {@code iat}를 싣는다. 그럼에도 빠진
     * 토큰을 만나면 "가장 오래된 토큰"으로 취급해 fail-closed 로 떨어뜨린다 — 인증 필터 한가운데서
     * NPE 로 500 을 내는 것보다 인증을 안 해 주는 쪽이 낫다.
     */
    public long getIssuedAtEpochSecond(String token) {
        Date issuedAt = parse(token).getIssuedAt();
        return issuedAt == null ? Long.MIN_VALUE : issuedAt.toInstant().getEpochSecond();
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
