package com.skhynix.chat.integration;

import com.skhynix.chat.shared.ChatClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실 Redis(Testcontainers)에 붙는 테스트의 공통 부모. 시간은 실제 시계를 쓴다 —
 * Redis 가 EXPIREAT 를 실제 시각과 비교하므로 고정 시계의 "다음 자정"이 과거가 되면 키가 즉시 사라진다.
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class RedisIntegrationSupport {

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redis;
    protected final ChatClock clock = new ChatClock();

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flush() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    protected static long ttlSeconds(String key) {
        Long ttl = redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return ttl == null ? -3 : ttl;
    }
}
