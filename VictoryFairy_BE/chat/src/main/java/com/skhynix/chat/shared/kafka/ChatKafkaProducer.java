package com.skhynix.chat.shared.kafka;

import com.skhynix.chat.global.config.ChatProperties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * chat-messages·chat-control 동기 발행. 브로커 ack 를 기다려 돌려준다.
 *
 * <p>프로듀서 자체의 상한(max.block.ms·request.timeout.ms·delivery.timeout.ms)도 application.yaml 에서
 * {@code chat.kafka.send-timeout-ms} 와 같은 값으로 묶었다. 여기서 기다리기를 포기한 레코드를 프로듀서가 뒤에서
 * 계속 재시도하면, 503 을 받은 메시지가 나중에 채팅방에 나타난다.
 */
@Component
@RequiredArgsConstructor
public class ChatKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ChatKafkaCodec codec;
    private final ChatProperties chatProperties;

    /**
     * @return 그 레코드의 파티션 오프셋 = msgId
     * @throws ChatPublishException ack 실패·시간 초과
     */
    public long sendMessage(ChatMessagePayload payload) {
        SendResult<String, String> result = send(ChatTopics.MESSAGES, payload.gameId(), codec.write(payload));
        return result.getRecordMetadata().offset();
    }

    /**
     * @throws ChatPublishException ack 실패·시간 초과
     */
    public void sendControl(ChatControlMessage message) {
        send(ChatTopics.CONTROL, message.kafkaKey(), codec.write(message));
    }

    private SendResult<String, String> send(String topic, String key, String value) {
        long timeoutMs = chatProperties.kafka().sendTimeoutMs();
        try {
            return kafkaTemplate.send(topic, key, value).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatPublishException("Kafka 발행 대기 중 인터럽트 topic=" + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ChatPublishException("Kafka 발행 실패 topic=" + topic, e);
        } catch (RuntimeException e) {
            // 메타데이터를 못 받으면(브로커 정지·토픽 없음) send() 자체가 max.block.ms 뒤 동기로 던진다.
            throw new ChatPublishException("Kafka 발행 실패 topic=" + topic, e);
        }
    }
}
