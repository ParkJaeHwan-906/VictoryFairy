package com.skhynix.user.cleanup.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link CleanupExecutionLock} 단위 테스트 — USER-EDC-24(파드 간 상호배제)의 실제 구현 지점.
 * Redis 자체는 목이라 실제 원자성(TTL 만료 경쟁 등)은 검증하지 못하고, "선점 성공/실패에 따라 올바른
 * {@link Optional}을 돌려주는가"·"내 토큰이 아니면 지우지 않는 release 스크립트를 쓰는가"만 검증한다.
 * 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 */
@ExtendWith(MockitoExtension.class)
class CleanupExecutionLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CleanupExecutionLock lock;

    @Test
    @DisplayName("[USER-EDC-24] 락 선점에 성공하면(setIfAbsent=true) 해제에 쓸 토큰이 담긴 Optional을 반환한다")
    void tryAcquire_setIfAbsentTrue_returnsPresentToken() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);

        // when
        Optional<String> token = lock.tryAcquire();

        // then
        assertThat(token).isPresent();
    }

    @Test
    @DisplayName("[USER-EDC-24, USER-EDC-25] 이미 다른 파드가 선점 중이면(setIfAbsent=false) 빈 Optional을 반환한다")
    void tryAcquire_setIfAbsentFalse_returnsEmpty() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        // when
        Optional<String> token = lock.tryAcquire();

        // then
        assertThat(token).isEmpty();
    }

    @Test
    @DisplayName("두 번 연속 tryAcquire를 호출하면 매번 서로 다른 토큰 값으로 선점을 시도한다"
            + "(내 락인지 판별하는 값이 매 실행마다 달라야 다른 파드와 안 섞인다)")
    void tryAcquire_generatesDifferentTokenPerCall() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);

        // when
        Optional<String> first = lock.tryAcquire();
        Optional<String> second = lock.tryAcquire();

        // then
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get()).isNotEqualTo(second.get());
    }

    @Test
    @DisplayName("release는 내가 받은 토큰을 그대로 실어 원자적 get-then-del 스크립트를 실행한다")
    void release_executesAtomicCompareAndDeleteScript() {
        // given
        String token = "token-123";

        // when
        lock.release(token);

        // then
        verify(redisTemplate).execute(any(RedisScript.class), any(java.util.List.class), eq(token));
    }
}
