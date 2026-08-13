package com.skhynix.user.auth.store;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisEmailVerificationStore implements EmailVerificationStore {

    private static final String CODE_PREFIX = "email:verify:code:";
    private static final String ATTEMPTS_PREFIX = "email:verify:attempts:";
    private static final String COOLDOWN_PREFIX = "email:verify:cooldown:";
    private static final String VERIFIED_PREFIX = "email:verify:verified:";

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        redisTemplate.opsForValue().set(CODE_PREFIX + email, code, CODE_TTL);
    }

    @Override
    public Optional<String> findCode(String email) {
        String code = redisTemplate.opsForValue().get(CODE_PREFIX + email);
        return Optional.ofNullable(code);
    }

    @Override
    public void invalidateCode(String email) {
        redisTemplate.delete(CODE_PREFIX + email);
        redisTemplate.delete(ATTEMPTS_PREFIX + email);
    }

    @Override
    public int incrementAttempts(String email) {
        // 최초 증가(값==1)일 때만 코드와 같은 TTL을 걸어 함께 만료되게 한다 — 매번 걸면 시도할 때마다
        // 만료가 밀려 코드보다 오래 사는 버그가 된다.
        String key = ATTEMPTS_PREFIX + email;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, CODE_TTL);
        }
        return attempts == null ? 0 : attempts.intValue();
    }

    @Override
    public int getAttempts(String email) {
        String value = redisTemplate.opsForValue().get(ATTEMPTS_PREFIX + email);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public void startCooldown(String email) {
        // 값은 의미 없다 — 키가 TTL 동안 존재한다는 사실 자체가 재발송 금지 신호다.
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + email, "1", COOLDOWN_TTL);
    }

    @Override
    public boolean isCoolingDown(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + email));
    }

    @Override
    public void markVerified(String email) {
        redisTemplate.opsForValue().set(VERIFIED_PREFIX + email, "1", VERIFIED_TTL);
    }

    @Override
    public boolean isVerified(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(VERIFIED_PREFIX + email));
    }

    @Override
    public void consumeVerified(String email) {
        redisTemplate.delete(VERIFIED_PREFIX + email);
    }
}
