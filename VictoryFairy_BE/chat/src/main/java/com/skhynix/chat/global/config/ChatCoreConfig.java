package com.skhynix.chat.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 역할과 무관하게 항상 켜지는 설정. 스케줄링은 방 수명(API)과 2단계의 하트비트·배처(게이트웨이)가 함께 쓴다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ChatProperties.class)
public class ChatCoreConfig {

    /**
     * KAFKA_BOOTSTRAP_SERVERS 가 비어 있으면 기동을 거부한다(CHAT-GC-7).
     * 이 검사가 없으면 스프링 기본값 localhost:9092 로 조용히 떠서 첫 전송에서야 503 이 난다.
     * 환경변수 자체가 없으면 placeholder 해석이 먼저 실패하므로 여기 오지 않는다.
     */
    public ChatCoreConfig(@Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException(
                    "KAFKA_BOOTSTRAP_SERVERS(spring.kafka.bootstrap-servers)가 비어 있다. Kafka 주소 없이 chat 앱은 기동하지 않는다");
        }
    }
}
