package com.skhynix.chat.integration;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트가 공유하는 Redis·Kafka 컨테이너. 테스트 JVM 하나에서 한 번만 띄운다(테스트 클래스마다 새로 띄우면 느리다).
 * 종료는 Testcontainers 의 Ryuk 이 맡는다. 이미지는 compose 와 같은 버전이다(Redis 는 7 계열, Kafka 는 apache/kafka:3.9.1).
 *
 * <p>Kafka 는 compose 와 같이 토픽 자동 생성을 끈다 — 앱이 토픽을 만들지 않는다는 CHAT-GC-9 를 브로커 쪽에서도 막아 둔다.
 * 호출하는 테스트 클래스에는 반드시 {@code @Testcontainers(disabledWithoutDocker = true)} 를 붙인다
 * (Docker 가 없는 CI 에서는 실패가 아니라 건너뛴다).
 */
public final class Containers {

    public static final String MESSAGES_TOPIC = "chat-messages";
    public static final String CONTROL_TOPIC = "chat-control";

    private static GenericContainer<?> redis;
    private static KafkaContainer kafka;

    private Containers() {
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);
            container.start();
            redis = container;
        }
        return redis;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            KafkaContainer container = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
            container.start();
            kafka = container;
        }
        return kafka;
    }

    public static String redisHost() {
        return redis().getHost();
    }

    public static int redisPort() {
        return redis().getMappedPort(6379);
    }

    public static String kafkaBootstrapServers() {
        return kafka().getBootstrapServers();
    }

    /** 앱이 하지 않는 토픽 생성을 테스트가 대신한다(인프라 선행 조건 2). 이미 있으면 그대로 둔다. */
    public static void createChatTopics(int messagePartitions) throws Exception {
        try (Admin admin = admin()) {
            var existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            if (!existing.contains(MESSAGES_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(MESSAGES_TOPIC, messagePartitions, (short) 1))).all()
                        .get(10, TimeUnit.SECONDS);
            }
            if (!existing.contains(CONTROL_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(CONTROL_TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    public static Admin admin() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        return Admin.create(props);
    }

    public static Map<String, Object> consumerProps() {
        return Map.of("bootstrap.servers", kafkaBootstrapServers());
    }

    /**
     * history-writer 그룹이 "기존 레코드를 건너뛰고 지금부터" 읽게 한다. 공유 브로커에는 다른 테스트가 만든 레코드가
     * 쌓여 있어서다. 그룹에 활성 멤버가 있으면(다른 컨텍스트가 살아 있음) 옮기지 못하고 넘어간다.
     *
     * <p>예전에는 각 파티션에 더미 레코드를 넣어 offset 0 을 피했다(엔트리 id {@code 0-0} 거부 결함 우회). 엔트리 id 가
     * {@code {offset}-1} 이 된 뒤로 그 우회는 없다 — 빈 토픽이면 E2E 의 첫 메시지가 offset 0 으로 그대로 적재된다.
     */
    public static synchronized void startHistoryWriterGroupFromEnd() throws Exception {
        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", kafkaBootstrapServers());
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(consumerProps);
                Admin admin = admin()) {
            Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    new java.util.HashMap<>();
            for (String topic : List.of(MESSAGES_TOPIC, CONTROL_TOPIC)) {
                var partitions = consumer.partitionsFor(topic).stream()
                        .map(i -> new org.apache.kafka.common.TopicPartition(i.topic(), i.partition())).toList();
                consumer.endOffsets(partitions).forEach((tp, end) ->
                        offsets.put(tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(end)));
            }
            try {
                admin.alterConsumerGroupOffsets("chat-history-writer", offsets).all().get(10, TimeUnit.SECONDS);
            } catch (Exception groupInUse) {
                // 활성 멤버가 있으면 거부된다 — 이미 떠 있는 컨슈머가 자기 위치를 갖고 있으므로 그대로 둔다
            }
        }
    }
}
