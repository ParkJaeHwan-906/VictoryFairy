package com.skhynix.user.community.store;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code StringRedisTemplate} 재사용 — 이메일 인증·프로필 한도·정리 락과 같은 인스턴스, 새 의존 없음.
 * 키 규약도 그쪽({@code profile:image:temp:count:{appId}})과 같은 {@code 기능:용도:{식별자}} 형태다.
 * 키는 TTL 로만 사라진다 — 지우는 경로·정리 배치가 없다.
 */
@Repository
@RequiredArgsConstructor
public class RedisPostViewWindowStore implements PostViewWindowStore {

    private static final String KEY_PREFIX = "community:post:view:";

    private static final Duration WINDOW_TTL = Duration.ofSeconds(300);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryOpen(Long postId, Long userAccountId) {
        // SET NX EX — 존재하면 아무것도 바꾸지 않는다(TTL 포함). 이 한 호출이 고정 창 계약 전부다.
        Boolean opened = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + postId + ":" + userAccountId, "1", WINDOW_TTL);
        if (opened == null) {
            // 정상 경로에서는 나오지 않는 값이다. 참으로 흡수하면 Redis 이상이 곧 무제한 집계가 된다.
            throw new IllegalStateException("조회 창을 열지 못했다");
        }
        return opened;
    }
}
