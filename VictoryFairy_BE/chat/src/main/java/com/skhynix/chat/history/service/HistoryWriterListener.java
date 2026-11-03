package com.skhynix.chat.history.service;

import com.skhynix.chat.history.config.HistoryWriterConfig;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatControlMessage;
import com.skhynix.chat.shared.kafka.ChatKafkaCodec;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatTopics;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * history-writer(CHAT-GC-96~102). 고정 그룹 {@code chat-history-writer} 로 두 토픽을 소비해 Redis 에 사본을 만든다.
 *
 * <p>읽을 수 없는 레코드는 WARN 후 건너뛴다(재시도해도 영원히 실패한다). Redis 실패는 던져서 컨테이너가 같은
 * 레코드부터 블로킹 재시도하게 한다({@link HistoryWriterConfig}).
 *
 * <p>새 그룹의 시작 위치는 earliest 다. 그룹이 처음 생길 때나 커밋 오프셋이 만료됐을 때 그 사이 메시지를 히스토리에서
 * 잃지 않으려는 선택이다(이미 지난 경기의 레코드는 그 방 키에 다음 자정 TTL 로 쓰였다가 사라진다).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.HISTORY_WRITER, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class HistoryWriterListener {

    private final ChatKafkaCodec codec;
    private final HistoryStreamWriter streamWriter;

    // clientIdPrefix 는 Micrometer 의 kafka.consumer.* 메트릭 client.id 태그로 이 그룹을 구분하게 한다(CHAT-GC-101).
    @KafkaListener(
            id = ChatTopics.HISTORY_WRITER_GROUP,
            groupId = ChatTopics.HISTORY_WRITER_GROUP,
            clientIdPrefix = ChatTopics.HISTORY_WRITER_GROUP,
            topics = {ChatTopics.MESSAGES, ChatTopics.CONTROL},
            containerFactory = HistoryWriterConfig.CONTAINER_FACTORY,
            properties = {
                    "auto.offset.reset=earliest",
                    "enable.auto.commit=false",
                    "allow.auto.create.topics=false"
            })
    public void onRecord(ConsumerRecord<String, String> record) {
        if (ChatTopics.MESSAGES.equals(record.topic())) {
            writeMessage(record);
        } else if (ChatTopics.CONTROL.equals(record.topic())) {
            writeControl(record);
        }
    }

    private void writeMessage(ConsumerRecord<String, String> record) {
        ChatMessagePayload payload;
        try {
            payload = codec.readMessage(record.value());
        } catch (RuntimeException e) {
            log.warn("chat-messages 레코드를 읽지 못해 건너뜀 partition={} offset={}", record.partition(),
                    record.offset(), e);
            return;
        }
        String gameId = payload.gameId() != null ? payload.gameId() : record.key();
        if (gameId == null) {
            log.warn("gameId 없는 chat-messages 레코드 건너뜀 partition={} offset={}", record.partition(),
                    record.offset());
            return;
        }
        // msgId = 레코드 오프셋. 엔트리 id 는 {offset}-1 (ChatRedisKeys.entryId)
        streamWriter.append(gameId, ChatStreamEntry.of(payload, record.offset()));
    }

    private void writeControl(ConsumerRecord<String, String> record) {
        ChatControlMessage message;
        try {
            message = codec.readControl(record.value());
        } catch (RuntimeException e) {
            log.warn("chat-control 레코드를 읽지 못해 건너뜀 partition={} offset={}", record.partition(),
                    record.offset(), e);
            return;
        }
        switch (message) {
            case BlindTombstone tombstone -> {
                if (tombstone.gameId() != null) {
                    streamWriter.blind(tombstone.gameId(), tombstone.msgId());
                }
            }
            // 종료 명령은 게이트웨이 몫이다.
            case SubscriptionCloseCommand ignored -> {
            }
        }
    }
}
