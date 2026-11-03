package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatKafkaCodec;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * 프로듀서가 돌려주는 msgId 가 정말 브로커가 부여한 파티션 오프셋인지, 그리고 브로커가 멈췄을 때 제한 시간 안에
 * ChatPublishException 으로 끝나는지를 실 Kafka 로 확인한다. 프로듀서 설정은 application.yaml 의 값과 같다.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatKafkaProducerKafkaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SEND_TIMEOUT_MS = 1500;

    private static DefaultKafkaProducerFactory<String, String> factory;
    private static ChatKafkaProducer producer;

    @BeforeAll
    static void setUp() throws Exception {
        Containers.createChatTopics(2);
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafkaBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) SEND_TIMEOUT_MS);
        factory = new DefaultKafkaProducerFactory<>(config);
        ChatProperties base = props();
        ChatProperties custom = new ChatProperties(base.role(), base.history(), base.recovery(), base.gateway(),
                base.rateLimit(), base.dedup(), new ChatProperties.Kafka(SEND_TIMEOUT_MS));
        producer = new ChatKafkaProducer(new KafkaTemplate<>(factory), new ChatKafkaCodec(MAPPER), custom);
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    private static ChatMessagePayload payload(String gameId, String content) {
        return new ChatMessagePayload(gameId, 7L, "닉", "OB", null, content, "2026-10-09T19:03:21.123+09:00");
    }

    /** 토픽 전체를 처음부터 읽어 key 가 일치하는 레코드를 모은다. */
    private static List<ConsumerRecord<String, String>> readAll(String topic, String key, int expected) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafkaBootstrapServers());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 15_000;
            while (found.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    @Test
    @Order(1)
    @DisplayName("[CHAT-GC-50] sendMessage 가 돌려준 msgId 는 브로커가 그 레코드에 부여한 파티션 오프셋과 같고, key=gameId 로 기록된다")
    void sendMessage_returnsBrokerAssignedOffset() throws Exception {
        String game = "G-" + UUID.randomUUID();

        long msgId = producer.sendMessage(payload(game, "안녕"));

        List<ConsumerRecord<String, String>> records = readAll("chat-messages", game, 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).offset()).isEqualTo(msgId);
        assertThat(records.get(0).key()).isEqualTo(game);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = MAPPER.readValue(records.get(0).value(), Map.class);
        assertThat(value.keySet()).containsExactlyInAnyOrder(
                "gameId", "senderId", "senderNickname", "teamCode", "profileImgUrl", "content", "sentAt");
        assertThat(value.get("content")).isEqualTo("안녕");
    }

    @Test
    @Order(2)
    @DisplayName("[CHAT-GC-7] 같은 방(key)의 메시지는 한 파티션에 들어가 offset 이 단조 증가하고, 같은 파티션을 공유하는 다른 방 메시지 때문에 번호가 띄엄띄엄하다(알려진 결과 1)")
    void sameRoom_offsetsIncreaseAndHaveGapsWhenPartitionIsShared() throws Exception {
        List<PartitionInfo> partitions = new KafkaConsumerHelper().partitions("chat-messages");
        int count = partitions.size();
        String roomA = "A-" + UUID.randomUUID();
        int target = Utils.toPositive(Utils.murmur2(roomA.getBytes())) % count;
        String roomB;
        do {
            roomB = "B-" + UUID.randomUUID();
        } while (Utils.toPositive(Utils.murmur2(roomB.getBytes())) % count != target);

        List<Long> offsetsA = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            offsetsA.add(producer.sendMessage(payload(roomA, "a" + i)));
            producer.sendMessage(payload(roomB, "b" + i));
        }

        assertThat(offsetsA).isSorted().doesNotHaveDuplicates();
        for (int i = 1; i < offsetsA.size(); i++) {
            assertThat(offsetsA.get(i) - offsetsA.get(i - 1)).as("사이에 다른 방 메시지가 끼었다").isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @Order(3)
    @DisplayName("[CHAT-GC-77] 제어 레코드: 툼스톤은 key=gameId, 종료 명령은 key=targetUserAccountId 로 chat-control 에 기록된다")
    void sendControl_keysAndValues() {
        String game = "G-" + UUID.randomUUID();
        String user = String.valueOf(900_000 + (int) (Math.random() * 99_999));

        producer.sendControl(BlindTombstone.of(game, 4200L));
        producer.sendControl(SubscriptionCloseCommand.evict(Long.parseLong(user), "pod-a"));

        assertThat(readAll("chat-control", game, 1)).singleElement().satisfies(record ->
                assertThat(MAPPER.readValue(record.value(), Map.class)).containsEntry("type", "blind").containsEntry("msgId", 4200));
        assertThat(readAll("chat-control", user, 1)).singleElement().satisfies(record ->
                assertThat(MAPPER.readValue(record.value(), Map.class)).containsEntry("type", "subscription-close")
                        .containsEntry("allRooms", true));
    }

    @Test
    @Order(10)
    @DisplayName("[CHAT-GC-62] 브로커가 응답하지 않으면 send-timeout 안에 ChatPublishException 이 되고, 브로커가 돌아오면 다시 발행되어 더 큰 offset 을 받는다")
    void brokerPaused_failsWithinTimeout_thenRecovers() throws Exception {
        String game = "G-" + UUID.randomUUID();
        long before = producer.sendMessage(payload(game, "정상"));
        var docker = Containers.kafka().getDockerClient();
        String containerId = Containers.kafka().getContainerId();

        docker.pauseContainerCmd(containerId).exec();
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> producer.sendMessage(payload(game, "브로커 정지 중")))
                    .isInstanceOf(ChatPublishException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("send-timeout(1.5s) + 여유").isLessThan(8_000);
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            long after = producer.sendMessage(payload(game, "복구 후"));
            assertThat(after).isGreaterThan(before);
        });
    }

    /** 파티션 수 조회용(소비자 없이 메타데이터만). */
    private static final class KafkaConsumerHelper {
        List<PartitionInfo> partitions(String topic) {
            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafkaBootstrapServers());
            p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
                return consumer.partitionsFor(topic);
            }
        }
    }
}
