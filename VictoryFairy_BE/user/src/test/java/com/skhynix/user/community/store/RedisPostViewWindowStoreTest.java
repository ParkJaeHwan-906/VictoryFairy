package com.skhynix.user.community.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RedisPostViewWindowStore} - SET NX EX 300 한 호출이 고정 창 계약 전부다. USER-CM-71~73, 78, 206.
 */
class RedisPostViewWindowStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RedisPostViewWindowStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        store = new RedisPostViewWindowStore(redisTemplate);
    }

    @Test
    @DisplayName("[AC-CM-71-1, AC-CM-73-1] 키는 community:post:view:{postId}:{accountId}, setIfAbsent(NX)로 300초 TTL을 걸고 새로 열렸으면 true다")
    void tryOpen_newWindow_usesSetIfAbsentWith300SecondsTtl() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);

        boolean opened = store.tryOpen(7L, 42L);

        assertThat(opened).isTrue();
        verify(valueOps).setIfAbsent(eq("community:post:view:7:42"), anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("[AC-CM-72-1, AC-CM-73-1] 이미 창이 열려 있으면 false이고, 만료를 연장하는 호출(expire/set 덮어쓰기)을 하지 않는다 - 고정 창")
    void tryOpen_existingWindow_returnsFalseWithoutExtendingTtl() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);

        boolean opened = store.tryOpen(7L, 42L);

        assertThat(opened).isFalse();
        verify(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verifyNoMoreInteractions(valueOps);
        verify(redisTemplate).opsForValue();
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    @DisplayName("[AC-CM-78-1] Redis가 null을 돌려주는 비정상 응답이면 true로 흡수하지 않고 예외로 올린다(무제한 집계 방지)")
    void tryOpen_nullResult_throws() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(null);

        assertThatThrownBy(() -> store.tryOpen(7L, 42L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[AC-CM-78-1] Redis 접근 예외는 그대로 전파된다(호출자가 세지 않음으로 흡수)")
    void tryOpen_redisDown_propagates() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> store.tryOpen(7L, 42L))
                .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("[AC-CM-71-1] (게시글, 계정) 쌍마다 키가 다르다 - 같은 계정이라도 다른 글은 별도 창이다")
    void tryOpen_keyDiffersPerPostAndAccount() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(true);

        store.tryOpen(1L, 5L);
        store.tryOpen(2L, 5L);
        store.tryOpen(1L, 6L);

        verify(valueOps).setIfAbsent(eq("community:post:view:1:5"), anyString(), any(Duration.class));
        verify(valueOps).setIfAbsent(eq("community:post:view:2:5"), anyString(), any(Duration.class));
        verify(valueOps).setIfAbsent(eq("community:post:view:1:6"), anyString(), any(Duration.class));
    }
}
