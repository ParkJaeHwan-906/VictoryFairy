package com.skhynix.quiz.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * ⚠ {@code prod} 전용인 이유: {@link RedisMessageListenerContainer}는 기동 시점에 Redis로 실제 구독
 * 연결을 맺는다. 프로파일 제한이 없으면 로컬·테스트에서 Redis 없이 앱을 띄울 수 없다(발행 측
 * {@link RedisPubSubPublisher}도 동일, 그 자리는 {@link InMemoryPublisher}가 채운다).
 */
@Configuration
@Profile("prod")
public class RealtimeRedisConfig {

    @Bean
    public RedisMessageListenerContainer realtimeEventListenerContainer(
            RedisConnectionFactory connectionFactory, RealtimeEventSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(RedisPubSubPublisher.CHANNEL));
        return container;
    }
}
