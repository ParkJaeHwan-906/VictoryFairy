package com.skhynix.chat.history.config;

import com.skhynix.chat.shared.ChatRoles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * history-writer 컨테이너 팩토리(CHAT-GC-96·100).
 *
 * <p>Redis 명령이 실패하면 리스너가 예외를 던지고, 이 에러 핸들러가 그 레코드로 되감아 1초 간격으로 <b>무한</b>
 * 재시도한다. 실패한 레코드부터는 오프셋을 커밋하지 않으므로 복구 뒤 밀린 레코드를 순서대로 따라잡는다.
 * 건너뛰어 Stream 에 구멍을 내지 않는다. 읽을 수 없는 레코드(깨진 JSON)는 리스너가 직접 건너뛰므로 여기 오지 않는다.
 */
@Configuration
@ConditionalOnBooleanProperty(name = ChatRoles.HISTORY_WRITER, matchIfMissing = true)
public class HistoryWriterConfig {

    public static final String CONTAINER_FACTORY = "historyWriterContainerFactory";

    private static final long RETRY_INTERVAL_MS = 1_000L;

    @Bean(CONTAINER_FACTORY)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ConcurrentKafkaListenerContainerFactory<String, String> historyWriterContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<?, ?> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure((ConcurrentKafkaListenerContainerFactory) factory, (ConsumerFactory) consumerFactory);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS));
        // Redis 가 내려가 있는 동안 매초 쌓이는 로그라 ERROR 로 두지 않는다.
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
