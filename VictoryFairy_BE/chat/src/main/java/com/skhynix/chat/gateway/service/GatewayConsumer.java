package com.skhynix.chat.gateway.service;

import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatControlMessage;
import com.skhynix.chat.shared.kafka.ChatKafkaCodec;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatTopics;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * 게이트웨이 컨슈머(CHAT-GC-86). chat-messages·chat-control 의 전 파티션을 <b>컨슈머 그룹 없이</b> {@code assign()} 한다.
 *
 * <ul>
 *   <li>파드마다 같은 레코드를 전부 받아 자기 구독자에게만 보낸다. 그룹 구독(@KafkaListener)이면 파드끼리 파티션을
 *       나눠 가져 다른 파드 구독자의 메시지를 못 받는다.</li>
 *   <li>기동 시 latest 부터 읽고 오프셋을 저장하지 않는다. 재시작 사이의 메시지는 SSE 로 보내지 않는다 —
 *       복구는 클라이언트의 Last-Event-ID 경로뿐이다.</li>
 *   <li>이 스레드는 방별 큐 적재만 한다(CHAT-GC-89). 종료 명령 처리도 emitter 를 기다리지 않는다
 *       ({@link com.skhynix.chat.realtime.SseFrameWriter#complete}).</li>
 * </ul>
 *
 * <p>파티션 목록은 주기적으로 다시 읽는다. 기동 후 늘어난 파티션은 처음부터 읽는다(새 파티션은 빈 상태로 생기므로
 * 그 사이 들어온 메시지를 놓치지 않는다). 기동 시 토픽이 없거나 브로커가 안 보이면 기동을 막지 않고 재시도한다.
 *
 * <p>Spring 의 ConsumerFactory 로 만들어 Boot 가 붙이는 Micrometer 바인딩(kafka.consumer.*)을 그대로 받는다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.GATEWAY, matchIfMissing = true)
@Slf4j
public class GatewayConsumer implements SmartLifecycle {

    public static final int PHASE = Integer.MAX_VALUE - 10;

    private static final List<String> TOPICS = List.of(ChatTopics.MESSAGES, ChatTopics.CONTROL);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final long REFRESH_INTERVAL_MS = 30_000L;
    private static final long UNASSIGNED_RETRY_MS = 5_000L;
    private static final long ERROR_BACKOFF_MS = 1_000L;
    private static final long STOP_JOIN_MS = 5_000L;

    private final ConsumerFactory<String, String> consumerFactory;
    private final ChatKafkaCodec codec;
    private final RoomBatcher batcher;
    private final SseEmitterRegistry registry;

    private final Set<String> initializedTopics = new HashSet<>();
    private volatile boolean running;
    private volatile Consumer<String, String> consumer;
    private Thread thread;

    @SuppressWarnings("unchecked")
    public GatewayConsumer(ConsumerFactory<?, ?> consumerFactory, ChatKafkaCodec codec, RoomBatcher batcher,
            SseEmitterRegistry registry) {
        this.consumerFactory = (ConsumerFactory<String, String>) consumerFactory;
        this.codec = codec;
        this.batcher = batcher;
        this.registry = registry;
    }

    @Override
    public void start() {
        // 재시작이면 다시 latest 부터다 — 이전 실행의 "처음 본 토픽" 기록을 지운다.
        initializedTopics.clear();
        running = true;
        thread = new Thread(this::run, "chat-gateway-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        Consumer<String, String> current = consumer;
        if (current != null) {
            current.wakeup();
        }
        if (thread != null) {
            try {
                thread.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void run() {
        Properties overrides = new Properties();
        // 그룹 없음 → 오프셋 커밋 없음. 자동 커밋을 켜면 group.id 가 없어 생성 자체가 실패한다.
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        overrides.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        // groupId=null: 전역 spring.kafka.consumer.group-id 가 없으므로 그룹 없이 만들어진다.
        try (Consumer<String, String> created = consumerFactory.createConsumer(null, "chat-gateway", null, overrides)) {
            consumer = created;
            loop(created);
        } catch (RuntimeException e) {
            log.error("게이트웨이 컨슈머가 비정상 종료됐다 — 이 파드의 SSE 실시간 전달이 멈춘다", e);
        } finally {
            consumer = null;
        }
    }

    private void loop(Consumer<String, String> kafka) {
        long nextRefreshAt = 0L;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextRefreshAt) {
                    refreshAssignment(kafka);
                    nextRefreshAt = now + (kafka.assignment().isEmpty() ? UNASSIGNED_RETRY_MS : REFRESH_INTERVAL_MS);
                }
                if (kafka.assignment().isEmpty()) {
                    sleep(UNASSIGNED_RETRY_MS);
                    continue;
                }
                ConsumerRecords<String, String> records = kafka.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : records) {
                    dispatch(record);
                }
            } catch (WakeupException e) {
                if (!running) {
                    return;
                }
            } catch (InterruptException e) {
                return;
            } catch (RuntimeException e) {
                log.warn("게이트웨이 컨슈머 오류, 재시도", e);
                sleep(ERROR_BACKOFF_MS);
            }
        }
    }

    private void refreshAssignment(Consumer<String, String> kafka) {
        Set<TopicPartition> wanted = new HashSet<>(kafka.assignment());
        Set<TopicPartition> firstTime = new HashSet<>();
        for (String topic : TOPICS) {
            List<PartitionInfo> partitions;
            try {
                partitions = kafka.partitionsFor(topic);
            } catch (WakeupException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("파티션 조회 실패 topic={} — 재시도한다", topic, e);
                continue;
            }
            if (partitions == null || partitions.isEmpty()) {
                if (!initializedTopics.contains(topic)) {
                    log.warn("토픽이 없다 topic={} — 앱은 토픽을 만들지 않는다(CHAT-GC-9). 재시도한다", topic);
                }
                continue;
            }
            boolean first = initializedTopics.add(topic);
            for (PartitionInfo info : partitions) {
                TopicPartition partition = new TopicPartition(info.topic(), info.partition());
                if (wanted.add(partition) && first) {
                    firstTime.add(partition);
                }
            }
        }
        Set<TopicPartition> current = kafka.assignment();
        if (wanted.equals(current)) {
            return;
        }
        Set<TopicPartition> added = new HashSet<>(wanted);
        added.removeAll(current);
        kafka.assign(wanted);
        Set<TopicPartition> addedLater = new HashSet<>(added);
        addedLater.removeAll(firstTime);
        if (!firstTime.isEmpty()) {
            kafka.seekToEnd(firstTime);
        }
        if (!addedLater.isEmpty()) {
            kafka.seekToBeginning(addedLater);
        }
        log.info("게이트웨이 assign 갱신 partitions={} (latest={} beginning={})", wanted.size(), firstTime.size(),
                addedLater.size());
    }

    private void dispatch(ConsumerRecord<String, String> record) {
        if (ChatTopics.MESSAGES.equals(record.topic())) {
            dispatchMessage(record);
        } else if (ChatTopics.CONTROL.equals(record.topic())) {
            dispatchControl(record);
        }
    }

    private void dispatchMessage(ConsumerRecord<String, String> record) {
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
        batcher.enqueueMessage(gameId, payload.senderId(), ChatMessageView.of(payload, record.offset()));
    }

    private void dispatchControl(ConsumerRecord<String, String> record) {
        ChatControlMessage message;
        try {
            message = codec.readControl(record.value());
        } catch (RuntimeException e) {
            log.warn("chat-control 레코드를 읽지 못해 건너뜀 partition={} offset={}", record.partition(),
                    record.offset(), e);
            return;
        }
        // 종류로 반드시 가른다. 종료 명령을 메시지 경로로 흘리면 구독자에게 data: 로 샌다(CHAT-GC-92).
        switch (message) {
            case BlindTombstone tombstone -> {
                if (tombstone.gameId() != null) {
                    batcher.enqueueDeleted(tombstone.gameId(), tombstone.msgId());
                }
            }
            case SubscriptionCloseCommand command -> registry.handleCloseCommand(command);
        }
    }

    // 정지 요청에 바로 반응하도록 잘게 잔다(wakeup 은 poll 만 깨운다).
    private void sleep(long millis) {
        long until = System.currentTimeMillis() + millis;
        try {
            while (running && System.currentTimeMillis() < until) {
                Thread.sleep(Math.min(100L, millis));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
