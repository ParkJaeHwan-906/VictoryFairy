package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.message.service.MessageDedupStore;
import com.skhynix.chat.message.service.MessageDedupStore.Claim;
import com.skhynix.chat.message.service.MessageDedupStore.ConfirmedValue;
import com.skhynix.chat.message.service.SendRateLimiter;
import com.skhynix.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** dedup 두 단계(PENDING → 확정)와 속도 제한 1초 고정 창을 실 Redis 로 확인한다. */
class SendPathRedisIT extends RedisIntegrationSupport {

    private static final String GAME = "G1";
    private static final String CLIENT_MSG = "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab";
    private static final String KEY = "chat:dedup:" + GAME + ":" + CLIENT_MSG;

    private MessageDedupStore dedup(long ttlSeconds) {
        ChatProperties base = props();
        ChatProperties custom = new ChatProperties(base.role(), base.history(), base.recovery(), base.gateway(),
                base.rateLimit(), new ChatProperties.Dedup(ttlSeconds), base.kafka());
        return new MessageDedupStore(redis, new ObjectMapper(), custom);
    }

    private SendRateLimiter limiter(int perSecond) {
        ChatProperties base = props();
        ChatProperties custom = new ChatProperties(base.role(), base.history(), base.recovery(), base.gateway(),
                new ChatProperties.RateLimit(perSecond), base.dedup(), base.kafka());
        return new SendRateLimiter(redis, custom);
    }

    // ---------- dedup ----------

    @Test
    @DisplayName("[CHAT-GC-60] 첫 요청은 PENDING 을 NX 로 선점하고 TTL 은 dedup.ttl-seconds 이내다")
    void claim_setsPendingWithTtl() {
        Claim claim = dedup(120).claim(GAME, CLIENT_MSG);

        assertThat(claim).isEqualTo(new Claim.Acquired(KEY));
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("PENDING");
        assertThat(ttlSeconds(KEY)).isBetween(118L, 120L);
    }

    @Test
    @DisplayName("[CHAT-GC-105] PENDING 중 같은 clientMsgId 가 다시 오면 InFlight 이고 키는 그대로 PENDING 이다")
    void claim_whilePending_isInFlight() {
        MessageDedupStore store = dedup(120);
        store.claim(GAME, CLIENT_MSG);

        assertThat(store.claim(GAME, CLIENT_MSG)).isInstanceOf(Claim.InFlight.class);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("[CHAT-GC-106] ack 후 확정하면 값이 JSON {msgId, content} 로 바뀌고, TTL 은 120초로 되돌아가지 않고 선점 시점부터 이어진다(KEEPTTL)")
    void confirm_replacesValueAndKeepsTtl() throws Exception {
        MessageDedupStore store = dedup(100);
        store.claim(GAME, CLIENT_MSG);
        Thread.sleep(1500);

        store.confirm(KEY, 1234L, "오늘 이긴다");

        assertThat(redis.opsForValue().get(KEY)).isEqualTo("{\"msgId\":1234,\"content\":\"오늘 이긴다\"}");
        assertThat(ttlSeconds(KEY)).as("선점 때의 TTL(100)에서 시간이 흐른 값이어야 한다").isBetween(90L, 98L);
    }

    @Test
    @DisplayName("[CHAT-GC-106] 확정된 키로 다시 claim 하면 저장된 {msgId, content} 로 Replay 한다")
    void claim_afterConfirm_isReplay() {
        MessageDedupStore store = dedup(120);
        store.claim(GAME, CLIENT_MSG);
        store.confirm(KEY, 1234L, "오늘 이긴다");

        assertThat(store.claim(GAME, CLIENT_MSG)).isEqualTo(new Claim.Replay(new ConfirmedValue(1234L, "오늘 이긴다")));
    }

    @Test
    @DisplayName("[CHAT-GC-106] 그사이 키가 사라졌으면(XX) confirm 이 TTL 없는 키를 새로 만들지 않는다")
    void confirm_afterKeyGone_doesNotCreateKeyWithoutTtl() {
        MessageDedupStore store = dedup(120);

        store.confirm(KEY, 1L, "x");

        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-62] release 로 키를 지우면 같은 clientMsgId 가 409 가 아니라 다시 선점된다")
    void release_allowsReclaim() {
        MessageDedupStore store = dedup(120);
        store.claim(GAME, CLIENT_MSG);

        store.release(KEY);

        assertThat(redis.hasKey(KEY)).isFalse();
        assertThat(store.claim(GAME, CLIENT_MSG)).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    @DisplayName("[CHAT-GC-106] TTL(120초 상당)이 지나면 같은 clientMsgId 는 새로 선점된다 — 여기서는 ttl 2초로 줄여 확인")
    void claim_afterTtlExpiry_isNewAcquisition() throws Exception {
        MessageDedupStore store = dedup(2);
        store.claim(GAME, CLIENT_MSG);
        store.confirm(KEY, 1L, "x");
        Thread.sleep(2300);

        assertThat(store.claim(GAME, CLIENT_MSG)).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    @DisplayName("[CHAT-GC-60] 같은 clientMsgId 라도 방(gameId)이 다르면 다른 키다")
    void claim_isPerRoom() {
        MessageDedupStore store = dedup(120);
        store.claim(GAME, CLIENT_MSG);

        assertThat(store.claim("OTHER", CLIENT_MSG)).isInstanceOf(Claim.Acquired.class);
    }

    @Test
    @DisplayName("[CHAT-GC-60] 동시에 같은 clientMsgId 로 들어와도 선점에 성공하는 요청은 정확히 하나다")
    void claim_concurrent_exactlyOneAcquires() throws Exception {
        MessageDedupStore store = dedup(120);
        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Claim>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return store.claim(GAME, CLIENT_MSG);
            }));
        }
        start.countDown();
        long acquired = 0;
        for (var f : results) {
            if (f.get() instanceof Claim.Acquired) {
                acquired++;
            }
        }
        pool.shutdownNow();

        assertThat(acquired).isEqualTo(1);
    }

    // ---------- 속도 제한 ----------

    @Test
    @DisplayName("[CHAT-GC-58] 같은 1초 창의 4번째 요청은 429 이고, 키는 정수 카운터이며 TTL 이 1초 이내다")
    void rateLimit_fourthRequestInSameSecond_isRejected() {
        SendRateLimiter limiter = limiter(3);

        limiter.check(7L);
        limiter.check(7L);
        limiter.check(7L);

        assertBusiness(() -> limiter.check(7L), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
        assertThat(redis.opsForValue().get("chat:rate:7")).isEqualTo("4");
        Long pttl = redis.getExpire("chat:rate:7", java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(pttl).isBetween(1L, 1000L);
    }

    @Test
    @DisplayName("[CHAT-GC-58] 창은 첫 요청 시점부터 1초 고정이다 — 1초 뒤에는 다시 허용된다")
    void rateLimit_windowResetsAfterOneSecond() throws Exception {
        SendRateLimiter limiter = limiter(3);
        for (int i = 0; i < 3; i++) {
            limiter.check(7L);
        }
        Thread.sleep(1200);

        limiter.check(7L);

        assertThat(redis.opsForValue().get("chat:rate:7")).isEqualTo("1");
    }

    @Test
    @DisplayName("[CHAT-GC-58] 사용자마다 독립이고 방과 무관하게(키에 gameId 없음) 한 창을 공유한다")
    void rateLimit_isPerUser() {
        SendRateLimiter limiter = limiter(1);
        limiter.check(7L);

        limiter.check(8L); // 다른 사용자는 영향 없음
        assertBusiness(() -> limiter.check(7L), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("[CHAT-GC-11] per-second=5 면 같은 초 5번째까지 통과하고 6번째가 429 이다")
    void rateLimit_customLimit() {
        SendRateLimiter limiter = limiter(5);
        for (int i = 0; i < 5; i++) {
            limiter.check(7L);
        }

        assertBusiness(() -> limiter.check(7L), ErrorCode.CHAT_RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("[CHAT-GC-58] INCR 과 EXPIRE 가 한 스크립트라 카운터 키는 항상 TTL 을 가진다(TTL 없는 키가 남아 영구 429 가 되지 않는다)")
    void rateLimit_counterKeyAlwaysHasTtl() {
        limiter(3).check(7L);

        assertThat(redis.getExpire("chat:rate:7", java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0L);
    }
}
