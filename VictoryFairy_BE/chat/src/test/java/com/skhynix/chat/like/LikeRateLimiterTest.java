package com.skhynix.chat.like;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.like.service.LikeRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 좋아요 사용자당 1초 고정 창 속도 제한(메모리). */
class LikeRateLimiterTest {

    private static LikeRateLimiter limiter(int perSecond) {
        return new LikeRateLimiter(new ChatLikesProperties(new ChatLikesProperties.RateLimit(perSecond), 300, 100, 10_000));
    }

    @Test
    @DisplayName("[CHAT-LK-16] 같은 사용자가 1초 창 안에 15회 시도하면 앞의 10회만 통과하고 뒤 5회는 거부된다")
    void sameSecond_fifteenAttempts_tenPass() {
        LikeRateLimiter limiter = limiter(10);

        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            results.add(limiter.tryAcquire(1L));
        }

        assertThat(results.subList(0, 10)).containsOnly(true);
        assertThat(results.subList(10, 15)).containsOnly(false);
    }

    @Test
    @DisplayName("[CHAT-LK-16] 창은 그 사용자의 첫 좋아요부터 1초라서, 1초가 지나면 한도가 다시 열린다")
    void windowEndsOneSecondAfterFirstAttempt_thenQuotaResets() throws Exception {
        LikeRateLimiter limiter = limiter(2);
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();

        Thread.sleep(1_100);

        assertThat(limiter.tryAcquire(1L)).as("새 창의 첫 시도").isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-LK-16] 창 한도는 사용자별로 따로 센다 — 한 사용자가 한도를 다 써도 다른 사용자는 통과한다")
    void quotaIsPerUser() {
        LikeRateLimiter limiter = limiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(1L);
        }

        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    @Test
    @DisplayName("[CHAT-LK-40] chat.likes.rate-limit.per-second=3 이면 같은 초 4번째부터 거부된다")
    void configuredPerSecondThree_fourthRejected() {
        LikeRateLimiter limiter = limiter(3);

        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isTrue();
        assertThat(limiter.tryAcquire(7L)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-LK-16] 한도 0 이면 모든 시도가 거부된다(경계값)")
    void zeroLimit_rejectsEverything() {
        LikeRateLimiter limiter = limiter(0);

        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-LK-16] 같은 사용자가 여러 스레드에서 동시에 40회 시도해도 정확히 10회만 통과한다")
    void concurrentAttempts_exactlyLimitPass() throws Exception {
        LikeRateLimiter limiter = limiter(10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire(5L)) {
                    passed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(passed.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("[CHAT-LK-18] 끝난 창을 걷어내도(evictExpired) 판정은 달라지지 않는다 — 열린 창은 남고 새 창은 정상으로 열린다")
    void evictExpired_keepsOpenWindowAndDoesNotChangeVerdicts() throws Exception {
        LikeRateLimiter limiter = limiter(1);
        assertThat(limiter.tryAcquire(1L)).isTrue();

        limiter.evictExpired();
        assertThat(limiter.tryAcquire(1L)).as("열린 창은 걷히지 않아 여전히 한도 초과").isFalse();

        Thread.sleep(1_100);
        limiter.evictExpired();
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }
}
