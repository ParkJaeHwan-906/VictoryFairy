package com.skhynix.user.profileimage.store;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * {@code StringRedisTemplate} 재사용 — 이메일 인증 저장소와 같은 인스턴스이고 새 의존은 없다.
 * 키 규약도 그쪽({@code email:verify:*})과 같은 {@code 기능:용도:{식별자}} 형태다.
 */
@Repository
@RequiredArgsConstructor
public class RedisProfileImageUploadLimitStore implements ProfileImageUploadLimitStore {

    private static final String COUNT_PREFIX = "profile:image:temp:count:";

    /**
     * 고정 창 길이. 30분이 지나면 키가 사라져 그 {@code appId} 에 다시 10회가 열린다.
     *
     * <p>⚠ 증가할 때마다 TTL 을 다시 거는 <b>갱신형으로 바꾸지 말 것</b> — 30분 안에 계속 올리는
     * 사용자는 창이 끝나지 않아 영영 갇히고, 반대로 만료가 계속 밀리는 방향으로 잘못 고치면 사실상
     * 무제한이 된다.
     */
    private static final Duration WINDOW_TTL = Duration.ofMinutes(30);

    /**
     * 저장 실패분 환불. <b>키가 있을 때만</b> 감소시키고 TTL 은 건드리지 않는다.
     *
     * <p>단순 {@code decrement()} 로 바꾸면 안 된다 — Redis 의 {@code DECR} 는 <b>키가 없으면 키를
     * 새로 만들어 {@code -1} 을 넣고, 그렇게 만들어진 키에는 TTL 이 없다</b>. 창(30분)이 지나 키가
     * 사라진 뒤 도착한 실패 요청 하나가 그 {@code appId} 에 영구 키를 만들어 버리면, 이후 한도는
     * 11회가 아니라 사실상 무제한이 된다 — 원래 막으려던 것보다 나쁜 구멍이다.
     *
     * <p>존재 확인과 감소를 나눠 보내면 그 사이에 창이 만료돼 같은 구멍이 열리므로, {@code
     * CleanupExecutionLock} 의 get→del 과 같은 방식으로 <b>Lua 스크립트에 묶어 원자적으로</b>
     * 처리한다.
     *
     * <p>{@code DECR}/{@code INCR} 는 이미 있는 키의 만료를 바꾸지 않으므로(만료를 지우는 것은
     * {@code SET} 류다) 고정 창 계약이 그대로 유지된다 — 남은 TTL 이 환불로 늘거나 초기화되지
     * 않는다.
     */
    private static final RedisScript<Long> REFUND_SCRIPT = RedisScript.of(
            // 창이 이미 만료된 뒤 도착한 환불은 아무 일도 하지 않는다(키를 되살리지 않는다).
            "if redis.call('exists', KEYS[1]) == 0 then return 0 end "
                    + "local left = redis.call('decr', KEYS[1]) "
                    // 증가 없이 환불만 들어오는 경로는 없지만, 값이 음수로 내려가면 그만큼 한도가
                    // 늘어나므로 0 에서 멈춘다(INCR 도 TTL 을 건드리지 않는다).
                    + "if left < 0 then return redis.call('incr', KEYS[1]) end "
                    + "return left", Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public int increment(String appId) {
        String key = COUNT_PREFIX + appId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            // 정상 경로에서는 나오지 않는 값이다. 0(=허용)으로 흡수하면 Redis 이상이 곧 무제한
            // 업로드가 되므로 fail-closed 로 막는다.
            throw new IllegalStateException("임시 업로드 카운터를 증가시키지 못했다");
        }
        if (count == 1L) {
            // 키가 새로 생긴 이번 한 번만 창을 연다(고정 창). 이후 증가에서는 만료를 건드리지 않는다.
            redisTemplate.expire(key, WINDOW_TTL);
        }
        return count.intValue();
    }

    @Override
    public void refund(String appId) {
        redisTemplate.execute(REFUND_SCRIPT, List.of(COUNT_PREFIX + appId));
    }
}
