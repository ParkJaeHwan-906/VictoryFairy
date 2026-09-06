package com.skhynix.user.profileimage.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
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
 * {@link RedisProfileImageUploadLimitStore} — appId 카운터의 <b>고정 창</b> 계약. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-24, 25, 29, 30.
 *
 * <p>이 계약의 핵심은 "TTL을 언제 거느냐"다: 키가 새로 생길 때(첫 INCR 결과가 1)만 걸고, 그 뒤로는
 * 절대 다시 걸지 않는다 — 갱신형(sliding)으로 바뀌면 계속 올리는 사용자가 영영 갇히거나(만료를 안 걺)
 * 반대로 창이 계속 밀려 사실상 무제한이 된다(매번 다시 걺).
 */
@ExtendWith(MockitoExtension.class)
class RedisProfileImageUploadLimitStoreTest {

    private static final String KEY = "profile:image:temp:count:app-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RedisProfileImageUploadLimitStore store;

    @Test
    @DisplayName("[USER-PI-24, 25, 29] 키가 새로 생기는 첫 증가(결과=1)에서만 TTL 30분을 건다")
    void increment_firstCall_setsTtlOnlyOnce() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(KEY)).willReturn(1L);

        int result = store.increment("app-1");

        assertThat(result).isEqualTo(1);
        verify(redisTemplate).expire(eq(KEY), eq(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("[USER-PI-29] 고정 창: 5번째 증가(결과=5, 키가 새로 생긴 게 아님)에서는 TTL을 다시 걸지 "
            + "않는다 — 갱신형으로 구현하면 이 테스트가 깨진다")
    void increment_fifthCall_doesNotResetTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(KEY)).willReturn(5L);

        int result = store.increment("app-1");

        assertThat(result).isEqualTo(5);
        verify(redisTemplate, never()).expire(eq(KEY), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("increment는 INCR 결과값을 그대로 돌려준다")
    void increment_returnsIncrementedValue() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(KEY)).willReturn(11L);

        assertThat(store.increment("app-1")).isEqualTo(11);
    }

    @Test
    @DisplayName("INCR 결과가 null이면(정상 경로에서 나오지 않는 값) fail-closed로 예외를 던진다")
    void increment_nullResult_throwsIllegalStateException() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(KEY)).willReturn(null);

        assertThatThrownBy(() -> store.increment("app-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- USER-PI-117, 118: refund는 Lua 스크립트로 처리하고 TTL을 건드리지 않는다 ----------

    @Test
    @DisplayName("[USER-PI-117] refund는 대상 키를 담아 Lua 스크립트를 실행한다")
    void refund_executesLuaScriptWithTargetKey() {
        given(redisTemplate.execute(any(RedisScript.class), anyList())).willReturn(1L);

        store.refund("app-1");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)));
    }

    @Test
    @DisplayName("[USER-PI-118] refund는 TTL을 건드리는 expire를 호출하지 않는다 — 고정 창 계약(TTL 불변)")
    void refund_neverTouchesExpire() {
        given(redisTemplate.execute(any(RedisScript.class), anyList())).willReturn(1L);

        store.refund("app-1");

        verify(redisTemplate, never()).expire(any(), any(Duration.class));
        verify(redisTemplate, never()).persist(any());
    }

    @Test
    @DisplayName("[USER-PI-119] 환불 시점에 키가 이미 만료돼 스크립트가 0을 반환해도 예외 없이 조용히 끝난다"
            + "(키를 새로 만들지 않는다 — 실제 EXISTS=0 재현은 임베디드 Redis가 없어 유닛으로는 스크립트 "
            + "호출까지만 고정)")
    void refund_whenScriptReportsMissingKey_completesWithoutError() {
        given(redisTemplate.execute(any(RedisScript.class), anyList())).willReturn(0L);

        assertThatCode(() -> store.refund("app-1")).doesNotThrowAnyException();

        verify(redisTemplate, never()).expire(any(), any(Duration.class));
    }
}
