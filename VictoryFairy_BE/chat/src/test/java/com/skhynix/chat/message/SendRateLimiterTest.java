package com.skhynix.chat.message;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.message.service.SendRateLimiter;
import com.skhynix.common.error.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class SendRateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private SendRateLimiter limiter(int perSecond) {
        ChatProperties base = props();
        ChatProperties custom = new ChatProperties(base.role(), base.history(), base.recovery(), base.gateway(),
                new ChatProperties.RateLimit(perSecond), base.dedup(), base.kafka());
        return new SendRateLimiter(redisTemplate, custom);
    }

    @SuppressWarnings("unchecked")
    private void counterReturns(Long... counts) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq("1")))
                .willReturn(counts[0], (Object[]) java.util.Arrays.copyOfRange(counts, 1, counts.length));
    }

    @Test
    @DisplayName("[CHAT-GC-58] 사용자 단위 키 chat:rate:{userAccountId} 로 1초 창 스크립트를 실행한다(방과 무관)")
    @SuppressWarnings("unchecked")
    void check_usesPerUserKeyWithOneSecondWindow() {
        counterReturns(1L);

        limiter(3).check(42L);

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("chat:rate:42")), eq("1"));
    }

    @Test
    @DisplayName("[CHAT-GC-58] 창 안의 1~3번째 요청은 통과하고 4번째는 429 CHAT_RATE_LIMIT_EXCEEDED 이다")
    void check_fourthRequestInWindow_isRejected() {
        counterReturns(1L, 2L, 3L, 4L);
        SendRateLimiter limiter = limiter(3);

        limiter.check(7L);
        limiter.check(7L);
        limiter.check(7L);

        assertBusiness(() -> limiter.check(7L), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("[CHAT-GC-11] chat.rate-limit.per-second=5 이면 5번째는 통과하고 6번째가 429 이다")
    void check_customLimit_isHonored() {
        counterReturns(5L, 6L);
        SendRateLimiter limiter = limiter(5);

        limiter.check(7L);

        assertBusiness(() -> limiter.check(7L), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("[CHAT-GC-65] Redis 가 실패하면 속도 제한을 건너뛴다(fail-open) — 예외를 던지지 않는다")
    @SuppressWarnings("unchecked")
    void check_redisFailure_failsOpen() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq("1")))
                .willThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> limiter(3).check(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[CHAT-GC-65] 스크립트 결과가 null 이면 통과시킨다")
    void check_nullCount_passes() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), eq("1"))).willReturn(null);

        assertThatCode(() -> limiter(3).check(7L)).doesNotThrowAnyException();
    }
}
