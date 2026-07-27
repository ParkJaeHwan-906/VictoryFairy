package com.skhynix.quiz.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 실시간 fan-out 버스의 수신 배선({@code prod} 전용). 컨테이너가 별도 스레드에서 채널을 구독하고 도착한
 * 메시지를 {@link RealtimeEventSubscriber}에게 넘긴다.
 *
 * <p>⚠ 이 설정이 {@code prod} 전용인 이유: {@link RedisMessageListenerContainer}는 기동 시점에 Redis로
 * 실제 구독 연결을 맺는다. 프로파일 제한이 없으면 로컬·테스트에서 Redis 없이 앱을 띄울 수 없게 된다
 * (발행 측 {@link RedisPubSubPublisher}도 같은 이유로 {@code prod} 전용, 그 자리는
 * {@link InMemoryPublisher}가 채운다).
 *
 * <p>연결 설정은 {@code spring.data.redis.*}(application.yaml)이며 운영 값은 k8s configmap
 * {@code app-config}의 {@code REDIS_HOST}/{@code REDIS_PORT}로 주입된다.
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
