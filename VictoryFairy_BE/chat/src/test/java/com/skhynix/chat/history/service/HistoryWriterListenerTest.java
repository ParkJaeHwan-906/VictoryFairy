package com.skhynix.chat.history.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatKafkaCodec;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatTopics;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.ObjectMapper;

class HistoryWriterListenerTest {

    private final ChatKafkaCodec codec = new ChatKafkaCodec(new ObjectMapper());
    private final HistoryStreamWriter streamWriter = mock(HistoryStreamWriter.class);
    private final HistoryWriterListener listener = new HistoryWriterListener(codec, streamWriter);

    private ConsumerRecord<String, String> messageRecord(long offset, ChatMessagePayload payload, String key) {
        return new ConsumerRecord<>(ChatTopics.MESSAGES, 0, offset, key, codec.write(payload));
    }

    private static ChatMessagePayload payload(String gameId) {
        return new ChatMessagePayload(gameId, 7L, "닉", "OB", null, "안녕", "2026-10-09T19:03:21.123+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-96] 고정 group id chat-history-writer 로 chat-messages·chat-control 두 토픽을 소비하고 auto commit·토픽 자동 생성은 끈다")
    void listener_isBoundToFixedGroupAndBothTopics() throws Exception {
        KafkaListener annotation = HistoryWriterListener.class
                .getMethod("onRecord", ConsumerRecord.class).getAnnotation(KafkaListener.class);

        assertThat(annotation.groupId()).isEqualTo("chat-history-writer");
        assertThat(annotation.topics()).containsExactlyInAnyOrder("chat-messages", "chat-control");
        assertThat(annotation.properties()).contains("enable.auto.commit=false", "allow.auto.create.topics=false");
        assertThat(annotation.clientIdPrefix()).isEqualTo("chat-history-writer");
    }

    @Test
    @DisplayName("[CHAT-GC-97] 메시지 레코드는 레코드 offset 을 msgId 로 하는 엔트리로 해당 방 Stream 에 append 된다")
    void messageRecord_appendsEntryWithRecordOffset() {
        listener.onRecord(messageRecord(4402L, payload("G1"), "G1"));

        ArgumentCaptor<ChatStreamEntry> entry = ArgumentCaptor.forClass(ChatStreamEntry.class);
        verify(streamWriter).append(org.mockito.ArgumentMatchers.eq("G1"), entry.capture());
        assertThat(entry.getValue().msgId()).isEqualTo(4402L);
        assertThat(entry.getValue().senderId()).isEqualTo(7L);
        assertThat(entry.getValue().content()).isEqualTo("안녕");
    }

    @Test
    @DisplayName("[CHAT-GC-97] payload 에 gameId 가 없으면 레코드 key 로 대체한다")
    void messageRecord_missingGameIdInPayload_fallsBackToKey() {
        listener.onRecord(messageRecord(1L, payload(null), "G-KEY"));

        verify(streamWriter).append(org.mockito.ArgumentMatchers.eq("G-KEY"), any());
    }

    @Test
    @DisplayName("[CHAT-GC-96] payload 에도 key 에도 gameId 가 없는 레코드는 건너뛴다")
    void messageRecord_noGameIdAnywhere_isSkipped() {
        listener.onRecord(messageRecord(1L, payload(null), null));

        verifyNoInteractions(streamWriter);
    }

    @Test
    @DisplayName("[CHAT-GC-100] 읽을 수 없는(깨진 JSON) 메시지 레코드는 예외 없이 건너뛴다 — 재시도해도 영원히 실패하므로 컨슈머를 막지 않는다")
    void messageRecord_malformedJson_isSkippedWithoutThrowing() {
        ConsumerRecord<String, String> broken = new ConsumerRecord<>(ChatTopics.MESSAGES, 0, 5L, "G1", "{not json");

        assertThatCode(() -> listener.onRecord(broken)).doesNotThrowAnyException();

        verifyNoInteractions(streamWriter);
    }

    @Test
    @DisplayName("[CHAT-GC-100] Redis 쓰기 실패는 예외로 올라가 컨테이너 에러 핸들러가 같은 레코드를 재시도하게 한다(건너뛰지 않는다)")
    void messageRecord_redisFailure_propagatesForRetry() {
        doThrow(new QueryTimeoutException("down")).when(streamWriter).append(anyString(), any());

        assertThatThrownBy(() -> listener.onRecord(messageRecord(1L, payload("G1"), "G1")))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-98] blind 툼스톤 레코드는 해당 방 blind 집합에 msgId 를 SADD 한다")
    void controlRecord_blindTombstone_addsToBlindSet() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(ChatTopics.CONTROL, 0, 9L, "G1",
                codec.write(BlindTombstone.of("G1", 4200L)));

        listener.onRecord(record);

        verify(streamWriter).blind("G1", 4200L);
    }

    @Test
    @DisplayName("[CHAT-GC-98] Redis 쓰기 실패는 툼스톤에서도 예외로 올라가 재시도된다")
    void controlRecord_blindRedisFailure_propagates() {
        doThrow(new QueryTimeoutException("down")).when(streamWriter).blind(anyString(), anyLong());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(ChatTopics.CONTROL, 0, 9L, "G1",
                codec.write(BlindTombstone.of("G1", 4200L)));

        assertThatThrownBy(() -> listener.onRecord(record)).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-5] 구독 종료 명령은 게이트웨이 몫이라 history-writer 는 Redis 에 아무것도 쓰지 않는다")
    void controlRecord_closeCommand_isIgnored() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(ChatTopics.CONTROL, 0, 9L, "7",
                codec.write(SubscriptionCloseCommand.evict(7L, "pod-a")));

        listener.onRecord(record);

        verifyNoInteractions(streamWriter);
    }

    @Test
    @DisplayName("[CHAT-GC-96] 알 수 없는 type·깨진 JSON 의 제어 레코드는 건너뛴다")
    void controlRecord_unknownOrMalformed_isSkipped() {
        listener.onRecord(new ConsumerRecord<>(ChatTopics.CONTROL, 0, 1L, "G1", "{\"type\":\"mystery\"}"));
        listener.onRecord(new ConsumerRecord<>(ChatTopics.CONTROL, 0, 2L, "G1", "garbage"));

        verify(streamWriter, never()).blind(anyString(), anyLong());
        verify(streamWriter, never()).append(anyString(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-98] gameId 가 없는 툼스톤은 건너뛴다")
    void controlRecord_tombstoneWithoutGameId_isSkipped() {
        listener.onRecord(new ConsumerRecord<>(ChatTopics.CONTROL, 0, 1L, null,
                "{\"type\":\"blind\",\"gameId\":null,\"msgId\":1}"));

        verifyNoInteractions(streamWriter);
    }

    @Test
    @DisplayName("관심 없는 토픽의 레코드는 무시한다")
    void unknownTopic_isIgnored() {
        listener.onRecord(new ConsumerRecord<>("other-topic", 0, 1L, "k", "v"));

        verifyNoInteractions(streamWriter);
    }
}
