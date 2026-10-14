package com.skhynix.quiz.quizroom.realtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 퀴즈방 풀이 중 인원의 Redis 명령. 경기 채팅 presence({@code chat} 모듈 {@code PresenceStore})와 같은 구조다:
 * ZSET {@code quiz-room:{roomId}:presence}(member=계정 id, score=마지막 생존 시각) + HASH
 * {@code quiz-room:{roomId}:presence-owner}(계정 id → 등록한 파드). 두 키는 해시 태그로 같은 슬롯에 둬 한 스크립트에서 다룬다.
 *
 * <p>소유자 기록이 있는 이유: 다른 파드로 재구독한 직후 옛 파드가 옛 구독을 닫으며 지우면 방금 등록한 계정이 빠진다.
 * 종료 시 제거는 소유자가 자기일 때만 한다. 시각은 스크립트 안의 Redis {@code TIME} 만 쓴다(파드 시계 어긋남 배제).
 *
 * <p>키 접두사가 {@code quiz:votes:*} 와 겹치지 않는다(오늘의 퀴즈 집계 키를 건드리지 않는다).
 * 예외는 그대로 던진다 — 삼키기·로그는 호출부 몫이다.
 */
@Component
public class QuizRoomPresenceStore {

    private static final String NOW = ""
            + "local t = redis.call('TIME') "
            + "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) ";

    // 배타 경계 "(" — score 가 정확히 경계인 member 는 남긴다(아래 countMany 의 포함 경계와 짝)
    private static final String PRUNE = ""
            + "local cutoff = string.format('(%d', now - expireMs) "
            + "local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', cutoff) "
            + "if #expired > 0 then "
            + "  for _, m in ipairs(expired) do redis.call('HDEL', KEYS[2], m) end "
            + "  redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff) "
            + "end ";

    /** ARGV: [1]=instanceId, [2]=소유자 덮어쓰기('1'/'0'), [3]=만료 ms, [4]=키 TTL 초, [5..]=계정 id. 반환: 인원. */
    private static final RedisScript<Long> TOUCH = RedisScript.of(NOW
            + "local expireMs = tonumber(ARGV[3]) "
            + "local score = string.format('%d', now) "
            + "for i = 5, #ARGV do "
            + "  redis.call('ZADD', KEYS[1], score, ARGV[i]) "
            + "  if ARGV[2] == '1' then redis.call('HSET', KEYS[2], ARGV[i], ARGV[1]) "
            + "  else redis.call('HSETNX', KEYS[2], ARGV[i], ARGV[1]) end "
            + "end "
            + PRUNE
            + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
            + "redis.call('EXPIRE', KEYS[2], ARGV[4]) "
            + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /** ARGV: [1]=만료 ms. 반환: 만료를 걷은 뒤 인원. */
    private static final RedisScript<Long> COUNT = RedisScript.of(NOW
            + "local expireMs = tonumber(ARGV[1]) "
            + PRUNE
            + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /** ARGV: [1]=instanceId, [2..]=계정 id. 소유자가 자기인 계정만 지운다. */
    private static final RedisScript<Long> LEAVE = RedisScript.of(""
            + "local removed = 0 "
            + "for i = 2, #ARGV do "
            + "  if redis.call('HGET', KEYS[2], ARGV[i]) == ARGV[1] then "
            + "    redis.call('HDEL', KEYS[2], ARGV[i]) "
            + "    redis.call('ZREM', KEYS[1], ARGV[i]) "
            + "    removed = removed + 1 "
            + "  end "
            + "end "
            + "return removed",
            Long.class);

    private final StringRedisTemplate redis;
    private final long expireMs;
    private final String keyTtlSeconds;

    public QuizRoomPresenceStore(StringRedisTemplate redis,
            @Value("${quiz.room.presence.expire-seconds:45}") long expireSeconds,
            @Value("${quiz.room.presence.key-ttl-seconds:300}") long keyTtlSeconds) {
        this.redis = redis;
        this.expireMs = Math.max(1L, expireSeconds) * 1000L;
        this.keyTtlSeconds = String.valueOf(Math.max(1L, keyTtlSeconds));
    }

    /** 구독 성립 — 소유자를 자기로 덮어쓴다. @return 반영 뒤 인원 */
    public long join(long roomId, List<Long> userAccountIds, String instanceId) {
        return touch(roomId, userAccountIds, instanceId, true);
    }

    /** 생존 갱신 — 이미 있는 소유자는 빼앗지 않는다. */
    public void refresh(long roomId, List<Long> userAccountIds, String instanceId) {
        touch(roomId, userAccountIds, instanceId, false);
    }

    public long count(long roomId) {
        Long count = redis.execute(COUNT, keys(roomId), String.valueOf(expireMs));
        return count == null ? 0L : count;
    }

    public void leave(long roomId, List<Long> userAccountIds, String instanceId) {
        if (userAccountIds.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(userAccountIds.size() + 1);
        args.add(instanceId);
        userAccountIds.forEach(id -> args.add(String.valueOf(id)));
        redis.execute(LEAVE, keys(roomId), args.toArray());
    }

    /**
     * REST 응답용 일괄 읽기(쓰기 없음). 왕복은 방 수와 무관하게 2회다(TIME 1 + ZCOUNT 파이프라인 1).
     * 만료 판정 경계는 {@link #PRUNE} 과 같다(score ≥ now − 만료).
     */
    public Map<Long, Long> countMany(Collection<Long> roomIds) {
        Map<Long, Long> counts = new LinkedHashMap<>();
        if (roomIds.isEmpty()) {
            return counts;
        }
        List<Long> ordered = List.copyOf(roomIds);
        Long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        double min = (now == null ? System.currentTimeMillis() : now) - expireMs;
        List<Object> results = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (Long roomId : ordered) {
                    ops.opsForZSet().count(presenceKey(roomId), min, Double.POSITIVE_INFINITY);
                }
                return null;
            }
        });
        // 결과 개수가 어긋나면 한 칸 밀린 값을 싣느니 전부 0 으로 둔다(RedisQuizVoteTally 와 같은 판단)
        if (results.size() != ordered.size()) {
            return counts;
        }
        for (int i = 0; i < ordered.size(); i++) {
            Object value = results.get(i);
            counts.put(ordered.get(i), value instanceof Number number ? number.longValue() : 0L);
        }
        return counts;
    }

    private long touch(long roomId, List<Long> userAccountIds, String instanceId, boolean overwrite) {
        if (userAccountIds.isEmpty()) {
            return count(roomId);
        }
        List<String> args = new ArrayList<>(userAccountIds.size() + 4);
        args.add(instanceId);
        args.add(overwrite ? "1" : "0");
        args.add(String.valueOf(expireMs));
        args.add(keyTtlSeconds);
        userAccountIds.forEach(id -> args.add(String.valueOf(id)));
        Long count = redis.execute(TOUCH, keys(roomId), args.toArray());
        return count == null ? 0L : count;
    }

    private static List<String> keys(long roomId) {
        return List.of(presenceKey(roomId), "quiz-room:{" + roomId + "}:presence-owner");
    }

    private static String presenceKey(long roomId) {
        return "quiz-room:{" + roomId + "}:presence";
    }
}
