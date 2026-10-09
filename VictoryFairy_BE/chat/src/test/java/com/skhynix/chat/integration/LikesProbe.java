package com.skhynix.chat.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 공유 Redis 컨테이너를 앱과 무관하게 직접 들여다보는 도구. 앱과 달리 프록시를 거치지 않는다.
 * {@code redis-cli MONITOR} 대신 {@code INFO commandstats} 의 호출 수 차이로 "어떤 명령이 몇 번 실행됐는가"를 본다.
 * {@code chat:likes} 를 구독해 실제로 발행된 메시지 원문을 모은다.
 */
final class LikesProbe implements AutoCloseable {

    private final LettuceConnectionFactory factory;
    final StringRedisTemplate template;
    private final RedisMessageListenerContainer container;
    private final io.lettuce.core.RedisClient nativeClient;
    private final io.lettuce.core.api.StatefulRedisConnection<String, String> nativeConnection;
    private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    LikesProbe() {
        this.factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()));
        factory.afterPropertiesSet();
        this.nativeClient = io.lettuce.core.RedisClient.create(
                "redis://" + Containers.redisHost() + ":" + Containers.redisPort());
        this.nativeConnection = nativeClient.connect();
        this.template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        this.container = new RedisMessageListenerContainer();
        container.setConnectionFactory((RedisConnectionFactory) factory);
        container.addMessageListener((message, pattern) ->
                messages.add(new String(message.getBody(), StandardCharsets.UTF_8)), ChannelTopic.of("chat:likes"));
        container.afterPropertiesSet();
        container.start();
    }

    /** 지금까지 chat:likes 로 실제 발행된 메시지 원문. */
    List<String> published() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /** {@code PUBSUB NUMSUB chat:likes}. 이 프로브의 구독 1개도 들어 있다. */
    long numsub() {
        Map<String, Long> counts = nativeConnection.sync().pubsubNumsub("chat:likes");
        return counts.getOrDefault("chat:likes", 0L);
    }

    /** 이 프로브를 뺀 NUMSUB — 게이트웨이 파드 수에 해당한다. */
    long gatewaySubscribers() {
        return numsub() - 1;
    }

    /** {@code INFO commandstats} 의 명령별 누적 호출 수. */
    Map<String, Long> commandCalls() {
        Properties info = template.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        Map<String, Long> calls = new HashMap<>();
        if (info == null) {
            return calls;
        }
        for (String key : info.stringPropertyNames()) {
            if (!key.startsWith("cmdstat_")) {
                continue;
            }
            String value = info.getProperty(key);
            for (String part : value.split(",")) {
                if (part.startsWith("calls=")) {
                    calls.put(key.substring("cmdstat_".length()), Long.parseLong(part.substring("calls=".length())));
                }
            }
        }
        return calls;
    }

    /** before 대비 호출 수가 늘어난 명령과 증가량. */
    Map<String, Long> callsSince(Map<String, Long> before) {
        Map<String, Long> now = commandCalls();
        Map<String, Long> delta = new HashMap<>();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            long diff = e.getValue() - before.getOrDefault(e.getKey(), 0L);
            if (diff > 0) {
                delta.put(e.getKey(), diff);
            }
        }
        return delta;
    }

    long dbSize() {
        Long size = template.execute((RedisCallback<Long>) connection -> connection.serverCommands().dbSize());
        return size == null ? -1 : size;
    }

    @Override
    public void close() {
        try {
            container.destroy();
        } catch (Exception ignored) {
            // 정리 중
        }
        nativeConnection.close();
        nativeClient.shutdown();
        factory.destroy();
    }
}
