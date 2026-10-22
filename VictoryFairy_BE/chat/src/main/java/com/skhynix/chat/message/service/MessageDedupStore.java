package com.skhynix.chat.message.service;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.redis.ChatRedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.SetCondition;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 전송 멱등 키 {@code chat:dedup:{gameId}:{clientMsgId}} 의 두 단계(PENDING → 확정).
 *
 * <p>Redis 명령이 실패하면 dedup 없이 진행한다(fail-open, CHAT-GC-65). 그 경우 {@link Claim.Unavailable} 이고
 * 확정·해제도 하지 않는다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MessageDedupStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatProperties chatProperties;

    /** 선점 결과. */
    public sealed interface Claim permits Claim.Acquired, Claim.InFlight, Claim.Replay, Claim.Unavailable {

        /** 이 요청이 키를 잡았다. produce 로 진행하고 끝나면 확정하거나 해제한다. */
        record Acquired(String key) implements Claim {
        }

        /** 같은 clientMsgId 의 첫 요청이 아직 ack 를 기다린다. 409(CHAT-GC-105). */
        record InFlight() implements Claim {
        }

        /** 이미 확정됐다. 그 값으로 같은 202 를 돌려준다(CHAT-GC-106). */
        record Replay(ConfirmedValue value) implements Claim {
        }

        /** Redis 실패. dedup 없이 진행한다. */
        record Unavailable() implements Claim {
        }
    }

    /** 확정값 직렬화 형식 {"msgId":..,"content":..}. */
    public record ConfirmedValue(long msgId, String content) {
    }

    public Claim claim(String gameId, String clientMsgId) {
        String key = ChatRedisKeys.dedup(gameId, clientMsgId);
        Duration ttl = Duration.ofSeconds(chatProperties.dedup().ttlSeconds());
        try {
            // 선점 실패 직후 키가 만료됐으면(GET 이 null) 한 번 더 선점을 시도한다.
            for (int attempt = 0; attempt < 2; attempt++) {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, ChatRedisKeys.DEDUP_PENDING, ttl);
                if (acquired == null) {
                    return new Claim.Unavailable();
                }
                if (acquired) {
                    return new Claim.Acquired(key);
                }
                String value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    return interpret(key, value);
                }
            }
            return new Claim.InFlight();
        } catch (DataAccessException e) {
            log.warn("dedup 선점 실패, 멱등 보장 없이 진행한다 key={}", key, e);
            return new Claim.Unavailable();
        }
    }

    /**
     * PENDING 을 확정값으로 바꾼다. KEEPTTL 이라 선점 시점의 TTL 이 이어지고, XX 라 그사이 키가 사라졌으면
     * TTL 없는 키를 새로 만들지 않는다.
     */
    public void confirm(String key, long msgId, String content) {
        try {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = objectMapper.writeValueAsString(new ConfirmedValue(msgId, content))
                    .getBytes(StandardCharsets.UTF_8);
            redisTemplate.execute((RedisCallback<Boolean>) connection -> connection.stringCommands()
                    .set(keyBytes, valueBytes, SetCondition.ifPresent(), Expiration.keepTtl()));
        } catch (DataAccessException e) {
            // 이미 202 를 줄 메시지다. 키가 PENDING 으로 남아 TTL 동안 재시도가 409 를 받을 뿐 중복 produce 는 없다.
            log.warn("dedup 확정 실패 key={}", key, e);
        }
    }

    /** produce 실패 시 선점을 푼다. 남기면 재시도가 produce 된 적 없는 메시지로 계속 409 를 받는다(CHAT-GC-62). */
    public void release(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("dedup 해제 실패 key={}", key, e);
        }
    }

    private Claim interpret(String key, String value) {
        if (ChatRedisKeys.DEDUP_PENDING.equals(value)) {
            return new Claim.InFlight();
        }
        try {
            return new Claim.Replay(objectMapper.readValue(value, ConfirmedValue.class));
        } catch (JacksonException e) {
            // 알 수 없는 값이면 다시 produce 하지 않는다. 중복 메시지보다 TTL 동안의 409 가 낫다.
            log.warn("dedup 값 형식 오류 key={}", key, e);
            return new Claim.InFlight();
        }
    }
}
