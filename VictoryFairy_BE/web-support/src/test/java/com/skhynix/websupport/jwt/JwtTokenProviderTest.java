package com.skhynix.websupport.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * subject를 내부 PK {@code id}가 아니라 외부 노출용 {@code uid}로 바꾼 계약을 고정한다.
 *
 * <p>스프링 컨텍스트 없이 {@link JwtTokenProvider}를 직접 생성해 검증하는 순수 단위 테스트다.
 * {@link JwtProperties}도 직접 값을 채워 생성하므로 {@code @ConfigurationProperties} 바인딩은
 * 거치지 않는다.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 33 bytes, HS256 요구(32B+) 충족
    private static final long ACCESS_VALIDITY_MS = 3 * 60 * 60 * 1000L; // 3h
    private static final long REFRESH_VALIDITY_MS = 14 * 24 * 60 * 60 * 1000L; // 14d

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(newProperties());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static JwtProperties newProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenValidity(ACCESS_VALIDITY_MS);
        properties.setRefreshTokenValidity(REFRESH_VALIDITY_MS);
        return properties;
    }

    @Test
    @DisplayName("createAccessToken(uid)으로 만든 토큰의 subject는 그 uid와 같고, access 토큰으로 식별된다")
    void createAccessToken_subjectIsUid_andIsNotRefreshToken() {
        // given
        String uid = UUID.randomUUID().toString();

        // when
        String token = tokenProvider.createAccessToken(uid);

        // then
        assertThat(tokenProvider.getUid(token)).isEqualTo(uid);
        assertThat(tokenProvider.isRefreshToken(token)).isFalse();
        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("createRefreshToken(uid)으로 만든 토큰의 subject는 그 uid와 같고, refresh 토큰으로 식별된다")
    void createRefreshToken_subjectIsUid_andIsRefreshToken() {
        // given
        String uid = UUID.randomUUID().toString();

        // when
        String token = tokenProvider.createRefreshToken(uid);

        // then
        assertThat(tokenProvider.getUid(token)).isEqualTo(uid);
        assertThat(tokenProvider.isRefreshToken(token)).isTrue();
        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    /**
     * 이번 변경의 핵심 목적: JWT payload는 서명될 뿐 암호화되지 않아 base64 디코드만으로 누구나 읽을 수
     * 있으므로, 내부 PK({@code id})가 payload에 절대 담기지 않아야 한다. {@link JwtTokenProvider}의
     * 공개 API(getUid 등)를 거치지 않고 payload를 직접 base64url 디코드해 claim 키 집합 자체를
     * 검증한다.
     */
    @Test
    @DisplayName("access 토큰 payload에는 내부 id claim이 없고 jti·sub·type·iat·exp만 존재하며, sub는 uid 문자열이다")
    void accessTokenPayload_hasNoInternalIdClaim() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();

        // when
        String token = tokenProvider.createAccessToken(uid);
        Map<String, Object> payload = decodePayload(token);

        // then
        assertThat(payload.keySet()).containsExactlyInAnyOrder("jti", "sub", "type", "iat", "exp");
        assertThat(payload.get("sub")).isEqualTo(uid);
        assertThat(payload).doesNotContainKey("id");
        assertThat(payload).doesNotContainKey("userId");
        assertThat(payload).doesNotContainKey("accountId");
    }

    @Test
    @DisplayName("refresh 토큰 payload에도 내부 id claim이 없고 jti·sub·type·iat·exp만 존재한다")
    void refreshTokenPayload_hasNoInternalIdClaim() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();

        // when
        String token = tokenProvider.createRefreshToken(uid);
        Map<String, Object> payload = decodePayload(token);

        // then
        assertThat(payload.keySet()).containsExactlyInAnyOrder("jti", "sub", "type", "iat", "exp");
        assertThat(payload.get("type")).isEqualTo("refresh");
        assertThat(payload).doesNotContainKey("id");
    }

    @Test
    @DisplayName("서명이 다른 시크릿으로 생성된(위조된) 토큰은 validateToken()이 false를 반환한다")
    void validateToken_signedWithDifferentSecret_returnsFalse() {
        // given
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret("fedcba9876543210fedcba9876543210");
        otherProperties.setAccessTokenValidity(ACCESS_VALIDITY_MS);
        otherProperties.setRefreshTokenValidity(REFRESH_VALIDITY_MS);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProperties);
        String tokenFromOtherProvider = otherProvider.createAccessToken(UUID.randomUUID().toString());

        // when & then
        assertThat(tokenProvider.validateToken(tokenFromOtherProvider)).isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 토큰 문자열은 validateToken()이 false를 반환한다")
    void validateToken_malformedToken_returnsFalse() {
        assertThat(tokenProvider.validateToken("not-a-valid-jwt")).isFalse();
    }

    @Test
    @DisplayName("유효하지 않은 토큰에 getUid()를 호출하면 JwtException이 발생한다")
    void getUid_malformedToken_throwsJwtException() {
        assertThatThrownBy(() -> tokenProvider.getUid("not-a-valid-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("getExpiration()은 발급 시각 + 유효기간에 해당하는 미래 시각을 반환한다")
    void getExpiration_returnsFutureExpiryTime() {
        // given
        String token = tokenProvider.createAccessToken(UUID.randomUUID().toString());

        // when
        LocalDateTime expiration = tokenProvider.getExpiration(token);

        // then
        assertThat(expiration).isAfter(LocalDateTime.now());
        assertThat(expiration).isBefore(LocalDateTime.now().plusHours(4));
    }

    // ---------- getIssuedAtEpochSecond (USER-ATI-8, 14, 21) ----------

    @Test
    @DisplayName("[USER-ATI-14] access 토큰의 getIssuedAtEpochSecond()는 payload의 iat(초 단위 NumericDate) "
            + "값과 정확히 일치한다 — createToken이 이미 issuedAt을 싣고 있어 claim 집합을 바꾸지 않는다")
    void getIssuedAtEpochSecond_matchesIatClaimInPayload() throws Exception {
        // given
        String uid = UUID.randomUUID().toString();

        // when
        String token = tokenProvider.createAccessToken(uid);
        long issuedAtEpochSecond = tokenProvider.getIssuedAtEpochSecond(token);
        Map<String, Object> payload = decodePayload(token);

        // then: JJWT의 iat claim은 초 단위 epoch(long)로 직렬화된다
        assertThat(issuedAtEpochSecond).isEqualTo(((Number) payload.get("iat")).longValue());
        // 실측 시각과도 몇 초 오차 이내로 일치해야 한다(플레이키 방지를 위한 느슨한 허용 범위)
        assertThat(issuedAtEpochSecond)
                .isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(5L));
    }

    @Test
    @DisplayName("[USER-ATI-21 관련 fail-closed] iat claim이 없는(그러나 서명은 유효한) 토큰이면 "
            + "getIssuedAtEpochSecond()는 Long.MIN_VALUE를 반환한다 — \"가장 오래된 토큰\"으로 취급해 "
            + "무효화 대조에서 항상 거절되게 하는 fail-closed 동작이다")
    void getIssuedAtEpochSecond_missingIatClaim_returnsLongMinValue() {
        // given: JwtTokenProvider의 createToken을 거치지 않고 같은 시크릿으로 iat 없는 토큰을 직접 만든다
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String tokenWithoutIat = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(UUID.randomUUID().toString())
                .claim("type", "access")
                .expiration(new java.util.Date(System.currentTimeMillis() + ACCESS_VALIDITY_MS))
                .signWith(key)
                .compact();

        // sanity check: 서명은 유효하되 iat만 빠졌다는 전제
        assertThat(tokenProvider.validateToken(tokenWithoutIat)).isTrue();

        // when & then
        assertThat(tokenProvider.getIssuedAtEpochSecond(tokenWithoutIat)).isEqualTo(Long.MIN_VALUE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodePayload(String token) {
        String[] parts = token.split("\\.");
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return objectMapper.readValue(payloadJson, Map.class);
    }
}
