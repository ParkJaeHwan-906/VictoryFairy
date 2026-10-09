package com.skhynix.chat.history.service;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Stream·blind 집합 쓰기(CHAT-GC-97·98·99). Redis 실패({@link DataAccessException})는 그대로 던진다 —
 * 재시도는 컨테이너 에러 핸들러가 한다(CHAT-GC-100).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.HISTORY_WRITER, matchIfMissing = true)
@Slf4j
public class HistoryStreamWriter {

    public static final String XADD_REJECTED_METRIC = "chat.history.xadd.rejected";

    // Redis 가 XADD 의 명시 id 를 거부할 때의 오류 문구. 어느 쪽이든 같은 레코드를 재시도해도 영원히 거부되므로
    // 에러 핸들러의 무한 재시도로 보내면 history-writer 가 그 자리에서 멈춘다 — 둘 다 건너뛰기로 처리한다.
    //  - "The ID specified in XADD is equal or smaller than the target stream top item"(역전·재전달)
    //  - "The ID specified in XADD must be greater than 0-0"(entryId 시퀀스가 1 이라 지금은 안 나오지만 방어)
    private static final String[] ID_REJECTED = {
            "equal or smaller than the target stream top item",
            "must be greater than 0-0"
    };

    private final StringRedisTemplate redisTemplate;
    private final ChatClock clock;
    private final XAddOptions xaddOptions;
    private final Counter xaddRejected;

    public HistoryStreamWriter(StringRedisTemplate redisTemplate, ChatClock clock, ChatProperties chatProperties,
            MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        // MAXLEN ~ : 근사 트리밍이라 노드 단위로만 잘려 정확히 max-len 이 아닐 수 있다(알려진 결과 4)
        this.xaddOptions = XAddOptions.trim(TrimOptions.maxLen(chatProperties.history().maxLen()).approximate());
        this.xaddRejected = Counter.builder(XADD_REJECTED_METRIC)
                .description("id 역전으로 거부되어 건너뛴 history-writer XADD 수")
                .register(meterRegistry);
    }

    /**
     * {@code XADD chat:game:{gameId} MAXLEN ~ {max-len} {offset}-1 ...} 후 {@code EXPIREAT 다음 00:00 KST}.
     *
     * <p>id 역전 거부(at-least-once 재전달, 또는 파티션 수 변경으로 방이 더 작은 오프셋 공간으로 옮겨 간 경우)는
     * 건너뛰고 WARN + 카운터다. 그때도 EXPIREAT 는 건다 — XADD 는 됐는데 EXPIREAT 에서 실패해 재시도된 레코드가 이
     * 경로로 오면, 여기서 건너뛰면 키가 TTL 없이 남는다.
     */
    public void append(String gameId, ChatStreamEntry entry) {
        String key = ChatRedisKeys.stream(gameId);
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(key)
                .withId(RecordId.of(ChatRedisKeys.entryId(entry.msgId())))
                .ofMap(entry.toFields());
        try {
            redisTemplate.opsForStream().add(record, xaddOptions);
        } catch (DataAccessException e) {
            if (!isIdRejected(e)) {
                throw e;
            }
            xaddRejected.increment();
            log.warn("XADD id 역전으로 건너뜀 gameId={} msgId={}", gameId, entry.msgId());
        }
        redisTemplate.expireAt(key, expireAt());
    }

    /** {@code SADD chat:blind:{gameId} {msgId}} 후 같은 EXPIREAT. */
    public void blind(String gameId, long msgId) {
        String key = ChatRedisKeys.blind(gameId);
        redisTemplate.opsForSet().add(key, String.valueOf(msgId));
        redisTemplate.expireAt(key, expireAt());
    }

    // 절대 시각이라 메시지마다 다시 걸어도 TTL 이 늘어나지 않는다(멱등).
    private Instant expireAt() {
        return clock.nextMidnight();
    }

    private static boolean isIdRejected(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                for (String phrase : ID_REJECTED) {
                    if (message.contains(phrase)) {
                        return true;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
