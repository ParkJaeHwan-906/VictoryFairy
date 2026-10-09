package com.skhynix.chat.shared.kafka;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.global.config.ChatProperties;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

class ChatKafkaProducerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatKafkaCodec codec = new ChatKafkaCodec(mapper);

    private ChatKafkaProducer producer(long timeoutMs) {
        ChatProperties base = props();
        ChatProperties custom = new ChatProperties(base.role(), base.history(), base.recovery(), base.gateway(),
                base.rateLimit(), base.dedup(), new ChatProperties.Kafka(timeoutMs));
        return new ChatKafkaProducer(kafkaTemplate, codec, custom);
    }

    private static SendResult<String, String> ack(String topic, long offset) {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), offset, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>(topic, "k", "v"), metadata);
    }

    private static ChatMessagePayload payload() {
        return new ChatMessagePayload("G1", 7L, "닉", "OB", null, "안녕", "2026-10-09T19:03:21.123+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-50] sendMessage 는 브로커 ack 의 RecordMetadata.offset() 을 msgId 로 돌려준다")
    void sendMessage_returnsRecordOffset() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(ack("chat-messages", 4402L)));

        assertThat(producer(3000).sendMessage(payload())).isEqualTo(4402L);
    }

    @Test
    @DisplayName("[CHAT-GC-63] 메시지는 토픽 chat-messages, key=gameId 로 보내고 value 에 msgId 없이 7개 필드만 담는다")
    @SuppressWarnings("unchecked")
    void sendMessage_usesGameIdKeyAndSevenFieldValue() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(ack("chat-messages", 1L)));

        producer(3000).sendMessage(payload());

        org.mockito.ArgumentCaptor<String> value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("chat-messages"),
                org.mockito.ArgumentMatchers.eq("G1"), value.capture());
        Map<String, Object> json = mapper.readValue(value.getValue(), Map.class);
        assertThat(json.keySet()).containsExactlyInAnyOrder(
                "gameId", "senderId", "senderNickname", "teamCode", "profileImgUrl", "content", "sentAt");
        assertThat(json).doesNotContainKey("msgId");
        assertThat(json.get("sentAt")).isEqualTo("2026-10-09T19:03:21.123+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-62] ack 가 send-timeout-ms 안에 오지 않으면 ChatPublishException 이다")
    void sendMessage_ackTimeout_throwsPublishException() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> producer(50).sendMessage(payload())).isInstanceOf(ChatPublishException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-62] 비동기 전송 실패(ExecutionException)는 ChatPublishException 이다")
    void sendMessage_failedFuture_throwsPublishException() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThatThrownBy(() -> producer(3000).sendMessage(payload())).isInstanceOf(ChatPublishException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-62] 메타데이터를 못 받아 send() 자체가 동기로 던져도 ChatPublishException 이다")
    void sendMessage_synchronousFailure_throwsPublishException() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willThrow(new KafkaException("Topic chat-messages not present in metadata"));

        assertThatThrownBy(() -> producer(3000).sendMessage(payload())).isInstanceOf(ChatPublishException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-77] blind 툼스톤은 토픽 chat-control, key=gameId, value {type, gameId, msgId} 로 발행한다")
    @SuppressWarnings("unchecked")
    void sendControl_blindTombstone() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(ack("chat-control", 1L)));

        producer(3000).sendControl(BlindTombstone.of("G1", 4200L));

        org.mockito.ArgumentCaptor<String> value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("chat-control"),
                org.mockito.ArgumentMatchers.eq("G1"), value.capture());
        assertThat(mapper.readValue(value.getValue(), Map.class))
                .isEqualTo(Map.of("type", "blind", "gameId", "G1", "msgId", 4200));
    }

    @Test
    @DisplayName("[CHAT-GC-34] 종료 명령은 key=targetUserAccountId 로 발행하고 value 에 5개 필드가 실린다")
    @SuppressWarnings("unchecked")
    void sendControl_closeCommand_keyedByTargetUser() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(ack("chat-control", 1L)));

        producer(3000).sendControl(SubscriptionCloseCommand.evict(7L, "pod-a"));

        org.mockito.ArgumentCaptor<String> value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq("chat-control"),
                org.mockito.ArgumentMatchers.eq("7"), value.capture());
        Map<String, Object> json = mapper.readValue(value.getValue(), Map.class);
        assertThat(json.keySet()).containsExactlyInAnyOrder(
                "type", "targetUserAccountId", "originInstanceId", "allRooms", "gameId");
        assertThat(json.get("type")).isEqualTo("subscription-close");
        assertThat(json.get("allRooms")).isEqualTo(true);
        assertThat(json.get("originInstanceId")).isEqualTo("pod-a");
    }

    @Test
    @DisplayName("[CHAT-GC-82] 제어 레코드 발행 실패도 ChatPublishException 으로 올라간다")
    void sendControl_failure_throwsPublishException() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> producer(50).sendControl(BlindTombstone.of("G1", 1L)))
                .isInstanceOf(ChatPublishException.class);
    }
}
