package com.skhynix.user.oauth.store;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 티켓 저장소 — 이메일 인증 저장소와 <b>같은 Redis 인스턴스</b>를 쓴다(새 인프라 의존 없음).
 *
 * <p>키 공간을 {@code email:verify:*} 와 분리한 것이 곧 "결과를 전역 인증완료 상태로 만들지 않는다"의
 * 구현이다.
 */
@Repository
@RequiredArgsConstructor
public class RedisOauthTicketStore implements OauthTicketStore {

    private static final String TICKET_PREFIX = "oauth:ticket:";
    private static final String CODE_PREFIX = "oauth:ticket:code:";
    private static final String ATTEMPTS_PREFIX = "oauth:ticket:attempts:";
    private static final String TARGET_PREFIX = "oauth:ticket:target:";
    private static final String COOLDOWN_PREFIX = "oauth:ticket:cooldown:";

    /** 세 종류 티켓 공통. 인가코드를 대신하는 값이라 길게 살려 둘 이유가 없다. */
    private static final Duration TICKET_TTL = Duration.ofMinutes(10);
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String issue(OauthTicket ticket) {
        String token = generateToken();
        redisTemplate.opsForValue().set(TICKET_PREFIX + token,
                objectMapper.writeValueAsString(ticket), TICKET_TTL);
        return token;
    }

    @Override
    public Optional<OauthTicket> find(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(TICKET_PREFIX + ticket);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, OauthTicket.class));
        } catch (RuntimeException e) {
            // 배포로 티켓 모양이 바뀌면 옛 값이 남아 있을 수 있다. 만료와 같게 취급한다(다시 로그인).
            return Optional.empty();
        }
    }

    @Override
    public void consume(String ticket) {
        redisTemplate.delete(TICKET_PREFIX + ticket);
        invalidateCode(ticket);
        // 쿨다운은 남긴다 — 티켓을 소비했다고 방금 보낸 메일이 없던 일이 되지는 않는다.
    }

    @Override
    public void saveCode(String ticket, String code, String targetEmail) {
        redisTemplate.opsForValue().set(CODE_PREFIX + ticket, code, CODE_TTL);
        redisTemplate.opsForValue().set(TARGET_PREFIX + ticket, targetEmail, CODE_TTL);
    }

    @Override
    public Optional<String> findCode(String ticket) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(CODE_PREFIX + ticket));
    }

    @Override
    public Optional<String> findTargetEmail(String ticket) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(TARGET_PREFIX + ticket));
    }

    @Override
    public void invalidateCode(String ticket) {
        redisTemplate.delete(CODE_PREFIX + ticket);
        redisTemplate.delete(ATTEMPTS_PREFIX + ticket);
        redisTemplate.delete(TARGET_PREFIX + ticket);
    }

    @Override
    public int incrementAttempts(String ticket) {
        // 최초 증가(값==1)일 때만 코드와 같은 TTL 을 건다 — 매번 걸면 시도할 때마다 만료가 밀려
        // 카운터가 코드보다 오래 살고, 그러면 새 코드가 남의 시도 이력을 물려받는다.
        String key = ATTEMPTS_PREFIX + ticket;
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, CODE_TTL);
        }
        return attempts == null ? 0 : attempts.intValue();
    }

    @Override
    public int getAttempts(String ticket) {
        String value = redisTemplate.opsForValue().get(ATTEMPTS_PREFIX + ticket);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public void startCooldown(String ticket) {
        // 값은 의미 없다 — 키가 TTL 동안 존재한다는 사실 자체가 재발송 금지 신호다.
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + ticket, "1", COOLDOWN_TTL);
    }

    @Override
    public boolean isCoolingDown(String ticket) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + ticket));
    }

    /**
     * 티켓은 사실상 <b>무기명 자격증명</b>이라 순차값·짧은 난수를 쓰면 남의 가입 티켓을 맞혀 그 신원으로
     * 계정을 만들 수 있다. 256비트 난수를 URL 안전 문자로 낸다.
     */
    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
