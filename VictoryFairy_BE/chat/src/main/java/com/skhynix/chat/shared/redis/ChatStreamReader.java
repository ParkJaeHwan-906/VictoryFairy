package com.skhynix.chat.shared.redis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Stream·blind 집합 읽기. Redis 실패는 {@link org.springframework.dao.DataAccessException} 그대로 던진다.
 * 503 으로 바꿀지, reset 을 보낼지는 호출부가 정한다.
 *
 * <p>경계는 전부 시퀀스를 생략한 불완전 id({@link ChatRedisKeys#boundary})의 <b>포함</b> 범위로 쓴다. Redis 가
 * 시작 경계는 {@code n-0}, 끝 경계는 {@code n-<최대>} 로 채우므로 엔트리 시퀀스(현재 1)가 무엇이든 정확하다.
 * <ul>
 *   <li>"cursor 미포함, 더 오래된 것": 끝 = {@code cursor-1} → {@code (cursor-1)-<최대>} 이하</li>
 *   <li>"lastId 미포함, 더 새로운 것": 시작 = {@code lastId+1} → {@code (lastId+1)-0} 이상</li>
 *   <li>"정확히 msgId": 시작·끝 = {@code msgId} → {@code msgId-0} ~ {@code msgId-<최대>}</li>
 * </ul>
 * 배타 표기({@code (id})는 Redis 6.2+ 전용이라 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ChatStreamReader {

    private final StringRedisTemplate redisTemplate;

    /**
     * XREVRANGE, 최신순. {@code beforeMsgId} 가 null 이면 최신부터, 있으면 그 offset 미포함으로 더 오래된 것부터 읽는다.
     */
    public List<ChatStreamEntry> latest(String gameId, Long beforeMsgId, int count) {
        Range<String> range;
        if (beforeMsgId == null) {
            range = Range.unbounded();
        } else {
            if (beforeMsgId <= 0) {
                return List.of();
            }
            range = Range.leftUnbounded(Range.Bound.inclusive(ChatRedisKeys.boundary(beforeMsgId - 1)));
        }
        return toEntries(redisTemplate.opsForStream()
                .reverseRange(ChatRedisKeys.stream(gameId), range, Limit.limit().count(count)));
    }

    /** XRANGE, 오래된 순. {@code afterMsgId} 미포함. Last-Event-ID 복구용(2단계). */
    public List<ChatStreamEntry> after(String gameId, long afterMsgId, int count) {
        Range<String> range = Range.rightUnbounded(Range.Bound.inclusive(ChatRedisKeys.boundary(afterMsgId + 1)));
        return toEntries(redisTemplate.opsForStream()
                .range(ChatRedisKeys.stream(gameId), range, Limit.limit().count(count)));
    }

    /** 가장 오래된 엔트리. 트리밍 판정(CHAT-GC-40)용(2단계). */
    public Optional<ChatStreamEntry> oldest(String gameId) {
        List<ChatStreamEntry> entries = toEntries(redisTemplate.opsForStream()
                .range(ChatRedisKeys.stream(gameId), Range.unbounded(), Limit.limit().count(1)));
        return entries.stream().findFirst();
    }

    /** offset 이 msgId 인 엔트리 한 건. */
    public Optional<ChatStreamEntry> find(String gameId, long msgId) {
        if (msgId < 0) {
            return Optional.empty();
        }
        String id = ChatRedisKeys.boundary(msgId);
        List<ChatStreamEntry> entries = toEntries(redisTemplate.opsForStream()
                .range(ChatRedisKeys.stream(gameId), Range.closed(id, id), Limit.limit().count(1)));
        return entries.stream().findFirst();
    }

    /** 주어진 msgId 중 blind 집합에 든 것. 빈 입력이면 Redis 를 부르지 않는다. */
    public Set<Long> blinded(String gameId, Collection<Long> msgIds) {
        if (msgIds.isEmpty()) {
            return Set.of();
        }
        Object[] members = msgIds.stream().map(String::valueOf).toArray();
        Map<Object, Boolean> result = redisTemplate.opsForSet().isMember(ChatRedisKeys.blind(gameId), members);
        Set<Long> blinded = new HashSet<>();
        if (result != null) {
            result.forEach((member, isMember) -> {
                if (Boolean.TRUE.equals(isMember)) {
                    blinded.add(Long.valueOf(member.toString()));
                }
            });
        }
        return blinded;
    }

    private static List<ChatStreamEntry> toEntries(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<ChatStreamEntry> entries = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            entries.add(ChatStreamEntry.fromFields(record.getId().getValue(), record.getValue()));
        }
        return entries;
    }
}
