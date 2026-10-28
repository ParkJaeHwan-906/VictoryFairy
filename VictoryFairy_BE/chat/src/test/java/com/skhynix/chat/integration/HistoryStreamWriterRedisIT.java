package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.history.service.HistoryStreamWriter;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * history-writer 의 Redis 쓰기를 실제 Redis 로 검증한다. 특히 id 역전 판정은 Redis 가 돌려주는 오류 문구
 * ("equal or smaller than the target stream top item") 매칭에 의존하므로 모킹으로는 증명되지 않는다.
 */
class HistoryStreamWriterRedisIT extends RedisIntegrationSupport {

    private static final String GAME = "20261009HTLG0";
    private static final String STREAM = "chat:game:" + GAME;

    private SimpleMeterRegistry meters;
    private HistoryStreamWriter writer;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        writer = new HistoryStreamWriter(redis, clock, props(), meters);
    }

    private static ChatStreamEntry entry(long offset, String content) {
        return ChatStreamEntry.of(new ChatMessagePayload(GAME, 7L, "닉", "OB", null, content,
                "2026-10-09T19:03:21.123+09:00"), offset);
    }

    private double rejectedCount() {
        var counter = meters.find(HistoryStreamWriter.XADD_REJECTED_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<MapRecord<String, Object, Object>> all() {
        return redis.opsForStream().range(STREAM, Range.unbounded());
    }

    @Test
    @DisplayName("[CHAT-GC-97] XADD 는 엔트리 id 를 {offset}-0 으로 하고 6개 필드(senderId·senderNickname·teamCode·profileImgUrl·content·sentAt)를 쓴다")
    void append_writesEntryWithOffsetId() {
        writer.append(GAME, entry(1234, "오늘 이긴다"));

        List<MapRecord<String, Object, Object>> records = all();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getId().getValue()).isEqualTo("1234-1");
        assertThat(records.get(0).getValue()).containsEntry("senderId", "7").containsEntry("senderNickname", "닉")
                .containsEntry("teamCode", "OB").containsEntry("content", "오늘 이긴다")
                .containsEntry("sentAt", "2026-10-09T19:03:21.123+09:00")
                .doesNotContainKey("profileImgUrl");   // null 은 필드를 빼고 쓴다
    }

    @Test
    @DisplayName("[CHAT-GC-97] append 직후 Stream 키에 다음 00:00 KST 까지의 TTL 이 걸리고, 두 번째 메시지 뒤에도 TTL 이 늘어나지 않는다(절대 시각)")
    void append_setsExpireAtNextMidnight_andDoesNotExtend() throws Exception {
        writer.append(GAME, entry(1, "a"));
        long first = ttlSeconds(STREAM);
        Thread.sleep(1500);
        writer.append(GAME, entry(2, "b"));
        long second = ttlSeconds(STREAM);

        long untilMidnight = clock.secondsUntilNextMidnight();
        assertThat(first).isBetween(untilMidnight - 3, untilMidnight + 1);
        assertThat(second).isLessThanOrEqualTo(first);
        assertThat(second).isBetween(untilMidnight - 2, untilMidnight + 1);
    }

    @Test
    @DisplayName("[CHAT-GC-97] 파티션의 첫 레코드(offset 0)에서 예외를 던지지 않고 다음 레코드(1-0)가 정상 적재된다 — 엔트리 id 0-0 은 Redis 가 거부(XADD must be greater than 0-0)한다")
    void append_firstRecordOfPartition_offsetZero() {
        org.assertj.core.api.Assertions.assertThatCode(() -> writer.append(GAME, entry(0, "토픽의 첫 메시지")))
                .as("offset 0 레코드가 예외를 던지면 컨테이너 에러 핸들러가 영원히 같은 레코드를 재시도한다").doesNotThrowAnyException();
        writer.append(GAME, entry(1, "두 번째"));

        assertThat(all()).extracting(r -> r.getId().getValue()).contains("0-1", "1-1");
        assertThat(all()).extracting(r -> r.getValue().get("content")).contains("두 번째");
    }

    @Test
    @DisplayName("[CHAT-GC-99] 같은 레코드를 두 번 받아도(at-least-once 재전달) 엔트리는 1개이고 예외 없이 거부 카운터만 1 오른다 — 실제 Redis 오류 문구로 판정")
    void append_sameRecordTwice_isSkippedAndCounted() {
        writer.append(GAME, entry(10, "a"));
        writer.append(GAME, entry(10, "a"));

        assertThat(all()).hasSize(1);
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("[CHAT-GC-99] 마지막 id 보다 작은 offset(파티션 수 변경 등으로 역전)도 건너뛰고 카운터를 올리며 기존 엔트리는 그대로다")
    void append_smallerIdThanTop_isSkippedAndCounted() {
        writer.append(GAME, entry(10, "a"));
        writer.append(GAME, entry(5, "late"));
        writer.append(GAME, entry(11, "b"));

        assertThat(all()).extracting(r -> r.getId().getValue()).containsExactly("10-1", "11-1");
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("[CHAT-GC-99] id 역전 거부 뒤에도 EXPIREAT 은 건다 — XADD 만 되고 EXPIREAT 에서 실패해 재전달된 레코드가 TTL 없는 키를 남기지 않게")
    void append_rejectedDuplicate_stillSetsExpiry() {
        writer.append(GAME, entry(10, "a"));
        redis.persist(STREAM); // EXPIREAT 만 실패한 상황을 흉내
        assertThat(ttlSeconds(STREAM)).isEqualTo(-1);

        writer.append(GAME, entry(10, "a")); // 재전달

        assertThat(ttlSeconds(STREAM)).isGreaterThan(0);
    }

    @Test
    @DisplayName("[CHAT-GC-100] id 역전이 아닌 Redis 오류(키 타입 충돌)는 삼키지 않고 예외로 올려 재시도되게 한다")
    void append_otherRedisError_propagates() {
        redis.opsForValue().set(STREAM, "not-a-stream");

        assertThatThrownBy(() -> writer.append(GAME, entry(1, "a"))).isInstanceOf(DataAccessException.class);
        assertThat(rejectedCount()).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-97] MAXLEN ~ 근사 트리밍: max-len 을 넘겨 쌓으면 오래된 엔트리부터 잘려 길이가 입력 건수보다 작아진다")
    void append_trimsApproximatelyToMaxLen() {
        ChatProperties base = props();
        ChatProperties small = new ChatProperties(base.role(), new ChatProperties.History(100), base.recovery(),
                base.gateway(), base.rateLimit(), base.dedup(), base.kafka());
        HistoryStreamWriter trimming = new HistoryStreamWriter(redis, clock, small, meters);

        for (long i = 1; i <= 1000; i++) {
            trimming.append(GAME, entry(i, "m" + i));
        }

        Long size = redis.opsForStream().size(STREAM);
        assertThat(size).isGreaterThanOrEqualTo(100).isLessThan(1000);
        // 오래된 쪽이 잘리고 최신은 남는다
        assertThat(all().get(all().size() - 1).getId().getValue()).isEqualTo("1000-1");
    }

    @Test
    @DisplayName("[CHAT-GC-98] blind 는 chat:blind:{gameId} 집합에 msgId 를 SADD 하고 같은 EXPIREAT 을 건다")
    void blind_addsToSetWithExpiry() {
        writer.blind(GAME, 4200L);

        assertThat(redis.opsForSet().isMember("chat:blind:" + GAME, "4200")).isTrue();
        assertThat(ttlSeconds("chat:blind:" + GAME)).isBetween(clock.secondsUntilNextMidnight() - 3,
                clock.secondsUntilNextMidnight() + 1);
    }

    @Test
    @DisplayName("[CHAT-GC-81] 같은 msgId 를 두 번 blind 해도 집합 원소는 1개다(멱등)")
    void blind_twice_isIdempotent() {
        writer.blind(GAME, 4200L);
        writer.blind(GAME, 4200L);

        assertThat(redis.opsForSet().size("chat:blind:" + GAME)).isEqualTo(1L);
    }

    @Test
    @DisplayName("[CHAT-GC-25] 방 단위 키는 방마다 독립이다 — 다른 방 Stream 에는 영향이 없다")
    void append_isPerRoom() {
        writer.append(GAME, entry(1, "a"));
        writer.append("OTHER", entry(1, "b"));

        assertThat(redis.opsForStream().size(STREAM)).isEqualTo(1L);
        assertThat(redis.opsForStream().size("chat:game:OTHER")).isEqualTo(1L);
    }
}
