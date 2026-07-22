package com.skhynix.user.auth.store;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link EmailVerificationStore}의 Redis 구현. 인증번호·시도 카운터·쿨다운·인증완료 상태를 전부
 * {@link StringRedisTemplate}로 키별 TTL과 함께 저장/조회/삭제한다. 키 네이밍·TTL은 포트 인터페이스의
 * 계약을 따른다(접두사 {@code email:verify:}).
 *
 * <p>{@link StringRedisTemplate}은 spring-boot-starter-data-redis가 자동 구성한다(연결 설정은
 * application.yaml의 {@code spring.data.redis.*}).
 *
 * <p><b>StringRedisTemplate 치트시트</b> (아래 메서드에서 쓰는 연산):
 * <ul>
 *   <li>{@code opsForValue().set(key, value, Duration)} → SET (TTL과 함께 저장, 기본 덮어쓰기)</li>
 *   <li>{@code opsForValue().get(key)} → GET (없으면 null)</li>
 *   <li>{@code opsForValue().increment(key)} → INCR (Long 반환, 원자적 +1)</li>
 *   <li>{@code expire(key, Duration)} → EXPIRE (키에 TTL 부여)</li>
 *   <li>{@code delete(key)} → DEL</li>
 *   <li>{@code hasKey(key)} → EXISTS (Boolean, null 가능 → Boolean.TRUE.equals 로 비교)</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class RedisEmailVerificationStore implements EmailVerificationStore {

    // ── 키 접두사: 인터페이스 계약(email:verify:*)과 1:1로 맞춘다. 최종 키 = 접두사 + email ──
    private static final String CODE_PREFIX = "email:verify:code:";
    private static final String ATTEMPTS_PREFIX = "email:verify:attempts:";
    private static final String COOLDOWN_PREFIX = "email:verify:cooldown:";
    private static final String VERIFIED_PREFIX = "email:verify:verified:";

    // ── TTL 상수: 요구사항에서 정한 유효시간. 저장할 때 이 값을 넘기면 Redis가 만료 후 자동 삭제한다. ──
    private static final Duration CODE_TTL = Duration.ofMinutes(5);      // 인증번호 · 시도 카운터
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60); // 재발송 쿨다운
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30); // 인증완료 상태

    /** 값 기반 문자열 저장소. opsForValue().set(key, value, Duration) / get(key) / delete(key) 등을 사용. */
    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        // 키 email:verify:code:{email} 에 code 를 TTL 5분으로 저장. set 은 기본 덮어쓰기라 이전 코드가 있으면 무효화됨.
        redisTemplate.opsForValue().set(CODE_PREFIX + email, code, CODE_TTL);
    }

    @Override
    public Optional<String> findCode(String email) {
        // 키의 값을 GET. 없거나 만료됐으면 null → Optional.empty(), 있으면 Optional.of(값).
        String code = redisTemplate.opsForValue().get(CODE_PREFIX + email);
        return Optional.ofNullable(code);
    }

    @Override
    public void invalidateCode(String email) {
        // 인증코드와 시도 카운터를 함께 삭제(검증 성공 1회용 소비 · 시도 초과 차단 · 재발송 전 정리).
        redisTemplate.delete(CODE_PREFIX + email);
        redisTemplate.delete(ATTEMPTS_PREFIX + email);
    }

    @Override
    public int incrementAttempts(String email) {
        // attempts 카운터를 INCR(+1)하고 증가 후 값을 반환. 키가 없으면 0에서 시작해 1이 됨.
        // 최초 증가(값==1)일 때만 코드와 같은 5분 TTL을 걸어, 코드와 함께 만료되게 한다
        // (매번 걸면 시도할 때마다 만료가 밀려 코드보다 오래 사는 버그 → 최초 1회만).
        String key = ATTEMPTS_PREFIX + email;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, CODE_TTL);
        }
        return attempts == null ? 0 : attempts.intValue();
    }

    @Override
    public int getAttempts(String email) {
        // 문자열로 저장돼 있으니 GET 후 정수 파싱. 키가 없으면 0.
         String value = redisTemplate.opsForValue().get(ATTEMPTS_PREFIX + email);
         return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public void startCooldown(String email) {
        // 쿨다운 마커. 값("1")은 의미 없고, "키가 60초간 존재한다"는 사실 자체가 재발송 금지 신호.
         redisTemplate.opsForValue().set(COOLDOWN_PREFIX + email, "1", COOLDOWN_TTL);
    }

    @Override
    public boolean isCoolingDown(String email) {
        // 쿨다운 키 존재 여부. hasKey 는 Boolean(래퍼)이라 null 가능 → Boolean.TRUE.equals 로 안전 비교.
         return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + email));
    }

    @Override
    public void markVerified(String email) {
        // 인증 완료 도장. 30분간 유효 — 그 안에 signup 해야 함.
         redisTemplate.opsForValue().set(VERIFIED_PREFIX + email, "1", VERIFIED_TTL);
    }

    @Override
    public boolean isVerified(String email) {
        // 인증완료 키 존재 여부. 키 부재(미인증·만료)는 동일하게 false.
         return Boolean.TRUE.equals(redisTemplate.hasKey(VERIFIED_PREFIX + email));
    }

    @Override
    public void consumeVerified(String email) {
        // 가입 성공 시 인증완료 상태 삭제 → 같은 인증으로 재가입 불가(1회용 소비).
         redisTemplate.delete(VERIFIED_PREFIX + email);
    }
}
