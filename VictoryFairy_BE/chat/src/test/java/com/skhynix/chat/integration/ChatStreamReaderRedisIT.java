package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

/**
 * Stream 읽기 경계(XREVRANGE/XRANGE 의 offset 포함·미포함)를 실 Redis 로 확인한다.
 * 엔트리 id 가 일부러 띄엄띄엄하다(10, 20, 30 ...) — 실제 msgId(Kafka offset)가 그렇기 때문이다.
 */
class ChatStreamReaderRedisIT extends RedisIntegrationSupport {

    private static final String GAME = "G1";

    private ChatStreamReader reader;

    @BeforeEach
    void seed() {
        reader = new ChatStreamReader(redis);
        for (long id = 10; id <= 100; id += 10) {
            redis.opsForStream().add(StreamRecords.newRecord().in("chat:game:" + GAME)
                    .withId(RecordId.of(id + "-1"))
                    .ofMap(Map.of("senderId", "7", "senderNickname", "닉" + id, "content", "내용" + id,
                            "sentAt", "t")));
        }
    }

    private static List<Long> ids(List<ChatStreamEntry> entries) {
        return entries.stream().map(ChatStreamEntry::msgId).toList();
    }

    @Test
    @DisplayName("[CHAT-GC-69] cursor 없이(상한 +) 읽으면 최신부터 내림차순으로 count 건을 돌려준다")
    void latest_withoutCursor_returnsNewestFirst() {
        assertThat(ids(reader.latest(GAME, null, 3))).containsExactly(100L, 90L, 80L);
    }

    @Test
    @DisplayName("[CHAT-GC-69] cursor 를 주면 그 offset 은 미포함이고 더 오래된 것부터 읽는다")
    void latest_withCursor_isExclusive() {
        assertThat(ids(reader.latest(GAME, 100L, 3))).containsExactly(90L, 80L, 70L);
        assertThat(ids(reader.latest(GAME, 40L, 10))).containsExactly(30L, 20L, 10L);
    }

    @Test
    @DisplayName("[CHAT-GC-69] 존재하지 않는 offset 을 cursor 로 줘도 그보다 작은 엔트리부터 정상 반환한다")
    void latest_withNonExistentCursor_returnsSmallerEntries() {
        assertThat(ids(reader.latest(GAME, 55L, 2))).containsExactly(50L, 40L);
    }

    @Test
    @DisplayName("[CHAT-GC-69] cursor 가 가장 오래된 엔트리이거나 0 이면 더 오래된 것이 없어 빈 목록이다")
    void latest_cursorAtOrBeforeOldest_isEmpty() {
        assertThat(reader.latest(GAME, 10L, 5)).isEmpty();
        assertThat(reader.latest(GAME, 0L, 5)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-68] 읽은 엔트리는 필드가 복원된다(msgId 는 id 의 offset 부분, 없는 필드는 null)")
    void latest_restoresFields() {
        ChatStreamEntry newest = reader.latest(GAME, null, 1).get(0);

        assertThat(newest.msgId()).isEqualTo(100L);
        assertThat(newest.senderId()).isEqualTo(7L);
        assertThat(newest.senderNickname()).isEqualTo("닉100");
        assertThat(newest.content()).isEqualTo("내용100");
        assertThat(newest.teamCode()).isNull();
        assertThat(newest.profileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구용 after 는 lastId 미포함으로 오름차순 count 건이다")
    void after_isExclusiveAscending() {
        assertThat(ids(reader.after(GAME, 40L, 3))).containsExactly(50L, 60L, 70L);
        assertThat(ids(reader.after(GAME, 45L, 2))).containsExactly(50L, 60L);
        assertThat(reader.after(GAME, 100L, 5)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-40] oldest 는 가장 오래된 엔트리, 빈 Stream 이면 빈 값이다")
    void oldest_returnsFirstEntry() {
        assertThat(reader.oldest(GAME)).map(ChatStreamEntry::msgId).contains(10L);
        assertThat(reader.oldest("EMPTY")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-79] find 는 {msgId}-0 엔트리 한 건을 찾고 없으면 빈 값이다")
    void find_exactEntry() {
        assertThat(reader.find(GAME, 50L)).map(ChatStreamEntry::content).contains("내용50");
        assertThat(reader.find(GAME, 55L)).isEmpty();
        assertThat(reader.find(GAME, -1L)).isEmpty();
        assertThat(reader.find("NO-STREAM", 50L)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-71] blinded 는 주어진 msgId 중 blind 집합에 든 것만 돌려주고, 빈 입력이면 빈 집합이다")
    void blinded_returnsOnlyMembers() {
        redis.opsForSet().add("chat:blind:" + GAME, "30", "70");

        assertThat(reader.blinded(GAME, List.of(10L, 30L, 70L, 90L))).isEqualTo(Set.of(30L, 70L));
        assertThat(reader.blinded(GAME, List.of())).isEmpty();
        assertThat(reader.blinded("NO-SET", List.of(1L, 2L))).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-69] 아주 큰 offset(Long 범위)도 cursor 로 처리된다")
    void latest_hugeCursor() {
        assertThat(ids(reader.latest(GAME, Long.MAX_VALUE, 2))).containsExactly(100L, 90L);
    }
}
