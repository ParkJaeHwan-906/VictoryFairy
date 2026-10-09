package com.skhynix.chat.message;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.message.service.MessageDedupStore;
import com.skhynix.chat.message.service.MessageDedupStore.Claim;
import com.skhynix.chat.message.service.MessageDedupStore.ConfirmedValue;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageDedupStoreTest {

    private static final String KEY = "chat:dedup:G1:3f9c2e10-aaaa-4bbb-8ccc-0123456789ab";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private MessageDedupStore store;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        store = new MessageDedupStore(redisTemplate, new ObjectMapper(), props());
    }

    @Test
    @DisplayName("[CHAT-GC-60] 선점은 키 chat:dedup:{gameId}:{clientMsgId} 에 PENDING 을 NX + TTL 120초로 건다")
    void claim_firstRequest_setsPendingWithNxAndTtl() {
        given(valueOps.setIfAbsent(KEY, "PENDING", Duration.ofSeconds(120))).willReturn(true);

        Claim claim = store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab");

        assertThat(claim).isEqualTo(new Claim.Acquired(KEY));
    }

    @Test
    @DisplayName("[CHAT-GC-105] 선점에 실패하고 기존 값이 PENDING 이면 InFlight(409) 이다")
    void claim_existingPending_isInFlight() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);
        given(valueOps.get(KEY)).willReturn("PENDING");

        assertThat(store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")).isInstanceOf(Claim.InFlight.class);
    }

    @Test
    @DisplayName("[CHAT-GC-106] 선점에 실패하고 기존 값이 확정 JSON 이면 그 {msgId, content} 로 Replay 한다")
    void claim_existingConfirmed_isReplayWithStoredValue() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);
        given(valueOps.get(KEY)).willReturn("{\"msgId\":1234,\"content\":\"오늘 이긴다\"}");

        Claim claim = store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab");

        assertThat(claim).isEqualTo(new Claim.Replay(new ConfirmedValue(1234L, "오늘 이긴다")));
    }

    @Test
    @DisplayName("[CHAT-GC-106] 알 수 없는 형식의 기존 값은 다시 produce 하지 않고 InFlight(409) 로 취급한다")
    void claim_existingGarbage_isTreatedAsInFlight() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false);
        given(valueOps.get(KEY)).willReturn("not-json{");

        assertThat(store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")).isInstanceOf(Claim.InFlight.class);
    }

    @Test
    @DisplayName("[CHAT-GC-60] 선점 실패 직후 키가 만료되어 GET 이 null 이면 한 번 더 선점을 시도해 성공하면 Acquired 이다")
    void claim_keyExpiredBetweenSetAndGet_retriesOnce() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(false, true);
        given(valueOps.get(KEY)).willReturn(null);

        Claim claim = store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab");

        assertThat(claim).isEqualTo(new Claim.Acquired(KEY));
        verify(valueOps, times(2)).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("[CHAT-GC-65] Redis 명령이 실패하면 Unavailable 이다(fail-open) — 예외를 던지지 않는다")
    void claim_redisFailure_isUnavailable() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new QueryTimeoutException("timeout"));

        assertThat(store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")).isInstanceOf(Claim.Unavailable.class);
    }

    @Test
    @DisplayName("[CHAT-GC-65] SET NX 가 null(파이프라인/트랜잭션 모드)을 돌려주면 Unavailable 이다")
    void claim_nullReply_isUnavailable() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(null);

        assertThat(store.claim("G1", "3f9c2e10-aaaa-4bbb-8ccc-0123456789ab")).isInstanceOf(Claim.Unavailable.class);
    }

    @Test
    @DisplayName("[CHAT-GC-62] release 는 키를 삭제한다")
    void release_deletesKey() {
        store.release(KEY);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("[CHAT-GC-62] release 중 Redis 가 실패해도 예외를 던지지 않는다(이미 503 을 내려보내는 중이다)")
    void release_redisFailure_isSwallowed() {
        doThrow(new QueryTimeoutException("timeout")).when(redisTemplate).delete(anyString());

        assertThatCode(() -> store.release(KEY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[CHAT-GC-106] confirm 중 Redis 가 실패해도 예외를 던지지 않는다(이미 produce 된 메시지의 202 를 막지 않는다)")
    void confirm_redisFailure_isSwallowed() {
        given(redisTemplate.execute(any(RedisCallback.class))).willThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> store.confirm(KEY, 1L, "x")).doesNotThrowAnyException();
    }
}
