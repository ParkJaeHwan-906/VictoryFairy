package com.skhynix.chat.gateway.service;

import com.skhynix.chat.global.config.ChatPresenceProperties;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 접속 인원 Redis 명령(CHAT-PR-5~10·20·30). ZSET {@code chat:presence:{gameId}} 와 HASH {@code chat:presence-owner:{gameId}}
 * 를 늘 같은 스크립트 안에서 함께 다뤄, 둘 중 하나만 바뀐 상태가 밖에서 보이지 않게 한다.
 *
 * <p><b>시각은 스크립트 안의 {@code TIME}(Redis 서버 시각)만 쓴다</b>(CHAT-PR-30). 파드 시계로 score 를 쓰거나 만료 경계를
 * 계산하면 파드 시계가 어긋난 만큼 일찍·늦게 뺀다. 종료 시 제거는 시각을 보지 않고 소유자만 본다.
 *
 * <p>예외는 그대로 던진다. 로그·건너뛰기는 호출부({@link PresenceTracker})가 한다. 블로킹 호출이라 presence 스레드에서만 부른다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
public class PresenceStore {

    /** 스크립트 한 번에 넣는 계정 수. 큰 방에서 스크립트 하나가 Redis 를 오래 붙잡지 않게 나눈다. */
    static final int CHUNK = 500;

    // 만료 member 를 걷고 그 소유 기록도 지운다(CHAT-PR-7). KEYS[1]=ZSET, KEYS[2]=HASH, 지역변수 now·expireMs·ttl 필요.
    // 범위는 배타 경계 "(" — score 가 정확히 경계인 member 는 남긴다.
    private static final String PRUNE = ""
            + "local cutoff = string.format('(%d', now - expireMs) "
            + "local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', cutoff) "
            + "if #expired > 0 then "
            + "  for _, m in ipairs(expired) do redis.call('HDEL', KEYS[2], m) end "
            + "  redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff) "
            + "  redis.call('EXPIRE', KEYS[1], ttl) "
            + "  redis.call('EXPIRE', KEYS[2], ttl) "
            + "end ";

    private static final String NOW = ""
            + "local t = redis.call('TIME') "
            + "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) ";

    /**
     * 등록(ARGV[2]='1', 소유자를 자기로 덮어쓴다 — CHAT-PR-5)·생존 갱신(ARGV[2]='0', 소유자가 없을 때만 자기로 — CHAT-PR-6).
     * ARGV: [1]=instanceId, [2]=덮어쓰기, [3]=만료 ms, [4]=키 TTL 초, [5..]=계정 id. 반환: 만료를 걷은 뒤 인원.
     */
    private static final RedisScript<Long> TOUCH = RedisScript.of(NOW
            + "local expireMs = tonumber(ARGV[3]) "
            + "local ttl = ARGV[4] "
            + "local score = string.format('%d', now) "
            + "for i = 5, #ARGV do "
            + "  redis.call('ZADD', KEYS[1], score, ARGV[i]) "
            + "  if ARGV[2] == '1' then redis.call('HSET', KEYS[2], ARGV[i], ARGV[1]) "
            + "  else redis.call('HSETNX', KEYS[2], ARGV[i], ARGV[1]) end "
            + "end "
            + PRUNE
            + "redis.call('EXPIRE', KEYS[1], ttl) "
            + "redis.call('EXPIRE', KEYS[2], ttl) "
            + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /** 주기 집계(CHAT-PR-7·12). ARGV: [1]=만료 ms, [2]=키 TTL 초. 반환: 만료를 걷은 뒤 인원. */
    private static final RedisScript<Long> COUNT = RedisScript.of(NOW
            + "local expireMs = tonumber(ARGV[1]) "
            + "local ttl = ARGV[2] "
            + PRUNE
            + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    /**
     * 종료 시 제거(CHAT-PR-8·9). 소유자가 자기일 때만 member 와 소유 기록을 함께 지운다 — 확인과 삭제 사이에 다른 파드의
     * 등록이 끼어 방금 소유자가 된 기록을 지우지 않게 한 스크립트로 묶는다. 소유 기록이 없으면 지우지 않는다(남의 것일 수 있다).
     * ARGV: [1]=instanceId, [2]=키 TTL 초, [3..]=계정 id. 반환: 지운 수.
     */
    private static final RedisScript<Long> LEAVE = RedisScript.of(""
            + "local removed = 0 "
            + "for i = 3, #ARGV do "
            + "  if redis.call('HGET', KEYS[2], ARGV[i]) == ARGV[1] then "
            + "    redis.call('HDEL', KEYS[2], ARGV[i]) "
            + "    redis.call('ZREM', KEYS[1], ARGV[i]) "
            + "    removed = removed + 1 "
            + "  end "
            + "end "
            + "if removed > 0 then "
            + "  redis.call('EXPIRE', KEYS[1], ARGV[2]) "
            + "  redis.call('EXPIRE', KEYS[2], ARGV[2]) "
            + "end "
            + "return removed",
            Long.class);

    private final StringRedisTemplate redis;
    private final String expireMs;
    private final String keyTtlSeconds;

    public PresenceStore(StringRedisTemplate redis, ChatPresenceProperties properties) {
        this.redis = redis;
        this.expireMs = String.valueOf(Math.max(1L, properties.expireSeconds()) * 1000L);
        this.keyTtlSeconds = String.valueOf(Math.max(1L, properties.keyTtlSeconds()));
    }

    /** 구독 성립. 소유자를 자기로 덮어쓴다. @return 반영 뒤 방 인원 */
    public long join(String gameId, List<Long> userAccountIds, String instanceId) {
        return touch(gameId, userAccountIds, instanceId, true);
    }

    /** 생존 갱신. 이미 있는 소유자를 빼앗지 않는다. 비워진 키도 다시 채운다(CHAT-PR-10). */
    public void refresh(String gameId, List<Long> userAccountIds, String instanceId) {
        touch(gameId, userAccountIds, instanceId, false);
    }

    /** 만료 member 를 걷고 인원을 센다. */
    public long count(String gameId) {
        Long count = redis.execute(COUNT, keys(gameId), expireMs, keyTtlSeconds);
        return count == null ? 0L : count;
    }

    /** 소유자가 자기인 계정만 지운다. "이 파드에 그 방 구독이 더 없는가"는 호출부가 먼저 거른다. */
    public void leave(String gameId, List<Long> userAccountIds, String instanceId) {
        for (List<Long> chunk : chunks(userAccountIds)) {
            List<String> args = new ArrayList<>(chunk.size() + 2);
            args.add(instanceId);
            args.add(keyTtlSeconds);
            chunk.forEach(id -> args.add(String.valueOf(id)));
            redis.execute(LEAVE, keys(gameId), args.toArray());
        }
    }

    private long touch(String gameId, List<Long> userAccountIds, String instanceId, boolean overwrite) {
        long count = 0L;
        for (List<Long> chunk : chunks(userAccountIds)) {
            List<String> args = new ArrayList<>(chunk.size() + 4);
            args.add(instanceId);
            args.add(overwrite ? "1" : "0");
            args.add(expireMs);
            args.add(keyTtlSeconds);
            chunk.forEach(id -> args.add(String.valueOf(id)));
            Long result = redis.execute(TOUCH, keys(gameId), args.toArray());
            count = result == null ? 0L : result;
        }
        return count;
    }

    private static List<String> keys(String gameId) {
        return List.of(ChatRedisKeys.presence(gameId), ChatRedisKeys.presenceOwner(gameId));
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        if (ids.size() <= CHUNK) {
            return List.of(ids);
        }
        List<List<Long>> result = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            result.add(ids.subList(from, Math.min(ids.size(), from + CHUNK)));
        }
        return result;
    }
}
