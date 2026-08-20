package com.skhynix.user.cleanup.support;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 파드 여러 개가 같은 회차를 동시에 돌지 않게 하는 Redis 락.
 *
 * <p>정리가 "지우기만" 하던 동안에는 겹쳐도 뒤늦은 쪽이 "이미 없음"으로 끝나 방치할 수 있었다.
 * <b>이관이 들어오면서 사정이 달라졌다</b> — 한쪽의 소유권 UPDATE 와 다른 쪽의 계정 삭제가 교차할 수
 * 있고, 그 결과는 계정별 재시도로 흡수되지 않는다.
 *
 * <p>ShedLock 같은 전용 라이브러리 대신 Redis 를 쓰는 이유는 <b>이미 있기 때문</b>이다(이메일 인증
 * 상태 저장소가 같은 {@code StringRedisTemplate} 를 쓴다) — 새 의존을 들이지 않는다.
 *
 * <p>회차(잡)마다 키가 다르다. 임시 프로필 이미지 정리(04:00)가 이 락을 <b>그대로 재사용</b>하는
 * 이유는 막으려는 사고가 같기 때문이다 — 파드가 여러 개면 같은 목록을 두 번 훑어 List/Delete 호출이
 * 배로 든다(삭제 자체는 멱등이라 데이터 피해는 없다). TTL 30분도 두 잡에 함께 맞는다.
 *
 * <p>⚠ 이 락은 완전하지 않다. TTL 이 먼저 끝나거나 네트워크가 갈라지면 두 파드가 겹칠 수 있다.
 * 그래서 "계정 1건 실패는 그 계정만 건너뛴다"는 안전망을 락이 있다고 걷어내면 안 된다.
 */
@Component
@RequiredArgsConstructor
public class CleanupExecutionLock {

    /** 만료 데이터 정리(매일 03:00) 회차 이름. 종전 키 {@code user:cleanup:expired-data:lock} 그대로다. */
    public static final String EXPIRED_DATA_JOB = "expired-data";

    /** 임시 프로필 이미지 정리(매일 04:00) 회차 이름. */
    public static final String TEMP_PROFILE_IMAGE_JOB = "temp-profile-image";

    // 회차마다 키가 다르다. 하나의 키를 공유하면 03:00 회차가 길어졌을 때 04:00 회차가 "다른 파드가
    // 진행 중"으로 오인돼 건너뛴다 - 서로 다른 일을 하는 두 잡이 서로를 막을 이유가 없다.
    private static final String KEY_PREFIX = "user:cleanup:";
    private static final String KEY_SUFFIX = ":lock";

    // 회차가 이보다 오래 걸리면 다른 파드가 들어올 수 있다. 대상 계정 수에 상한이 없어 이론상
    // 초과가 가능하지만(계정당 단문 4개), 그때는 위 안전망이 받는다. 반대로 TTL 을 없애면 파드가
    // 중간에 죽었을 때 락이 영영 풀리지 않아 정리가 통째로 멈춘다 — 그쪽이 훨씬 위험하다.
    private static final Duration TTL = Duration.ofMinutes(30);

    // 내 것이 아닌 락을 지우지 않도록 get→del 을 원자적으로 묶는다. 나눠 쓰면 그 사이에 TTL 이
    // 끝나고 다른 파드가 새로 잡은 락을 지워 버리는 창이 생긴다.
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                    + "else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 만료 데이터 정리 전용 단축 형태 — 기존 호출자·기존 키를 그대로 두려고 남긴다.
     *
     * @return 선점에 성공하면 해제에 쓸 토큰, 이미 다른 파드가 잡고 있으면 비어 있음
     */
    public Optional<String> tryAcquire() {
        return tryAcquire(EXPIRED_DATA_JOB);
    }

    /**
     * @param job 회차 이름. 이 값이 곧 락 키를 가르므로 잡마다 서로 다른 상수를 쓴다
     * @return 선점에 성공하면 해제에 쓸 토큰, 이미 다른 파드가 잡고 있으면 비어 있음
     */
    public Optional<String> tryAcquire(String job) {
        String token = UUID.randomUUID().toString();
        boolean acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key(job), token, TTL));
        return acquired ? Optional.of(token) : Optional.empty();
    }

    public void release(String token) {
        release(EXPIRED_DATA_JOB, token);
    }

    public void release(String job, String token) {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(key(job)), token);
    }

    private String key(String job) {
        return KEY_PREFIX + job + KEY_SUFFIX;
    }
}
