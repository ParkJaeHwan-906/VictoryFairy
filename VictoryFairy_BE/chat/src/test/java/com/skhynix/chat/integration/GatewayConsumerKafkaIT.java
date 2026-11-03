package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.gateway.service.GatewayConsumer;
import com.skhynix.chat.gateway.service.RoomBatcher;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatKafkaCodec;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.support.SseCapture;
import com.skhynix.chat.support.SseCapture.Event;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * 게이트웨이 컨슈머를 실 Kafka 에 붙여 본다 — 그룹 없는 assign, 기동 시 latest, 파드별 독립 수신, 제어 레코드 분기.
 * 컨슈머·배처·쓰기 풀·레지스트리를 운영과 같은 구성으로 직접 조립한다(Redis·DB 는 없다).
 */
@Testcontainers(disabledWithoutDocker = true)
class GatewayConsumerKafkaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ChatKafkaCodec CODEC = new ChatKafkaCodec(MAPPER);

    private static KafkaProducer<String, String> producer;
    private final List<Stack> stacks = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        Containers.createChatTopics(2);
        producer = newProducer(Containers.kafkaBootstrapServers());
    }

    @AfterEach
    void tearDown() {
        stacks.forEach(Stack::stop);
    }

    private static KafkaProducer<String, String> newProducer(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(p);
    }

    /** 운영 조립과 같은 게이트웨이 한 벌(파드 하나). */
    static final class Stack {
        final SseFrameWriter writer = new SseFrameWriter(props(30, 1000));
        final SseEmitterRegistry registry = new SseEmitterRegistry(writer);
        final RoomBatcher batcher = new RoomBatcher(registry, writer, props(30, 1000));
        final GatewayConsumer consumer;

        Stack(String bootstrap) {
            // 그룹 id 를 두지 않는다 — application.yaml 의 spring.kafka.consumer 와 같은 구성
            DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false));
            this.consumer = new GatewayConsumer(factory, CODEC, batcher, registry);
        }

        void start() {
            writer.start();
            registry.start();
            batcher.start();
            consumer.start();
        }

        void stop() {
            consumer.stop();
            batcher.stop();
            writer.stop();
            registry.stop();
        }
    }

    private Stack newStack() {
        Stack stack = new Stack(Containers.kafkaBootstrapServers());
        stacks.add(stack);
        return stack;
    }

    private static ChatMessagePayload payload(String gameId, long senderId, String content) {
        return new ChatMessagePayload(gameId, senderId, "닉" + senderId, "OB", null, content, "2026-10-09T19:03:21.123+09:00");
    }

    private static long sendMessage(String gameId, long senderId, String content) throws Exception {
        return producer.send(new ProducerRecord<>("chat-messages", gameId, CODEC.write(payload(gameId, senderId, content))))
                .get(10, TimeUnit.SECONDS).offset();
    }

    private static void sendRaw(String topic, String key, String value) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
    }

    /** 컨슈머가 assign 을 마쳐 실제로 수신 중임을 확인한다 — 프로브 방에 메시지가 전달될 때까지 주기적으로 보낸다. */
    private static void awaitReady(Stack stack) {
        String probeRoom = "PROBE-" + UUID.randomUUID();
        ChatSubscription probe = stack.registry.register(probeRoom, 900_000L, false);
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(400)).until(() -> {
            sendMessage(probeRoom, 1L, "probe");
            return !SseCapture.dataEvents(probe.emitter()).isEmpty();
        });
        stack.registry.closeSubscriptions(probeRoom, 900_000L);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messageItems(Event event) {
        return MAPPER.readValue(event.data(), List.class);
    }

    private static List<Long> receivedMsgIds(ChatSubscription subscription) {
        List<Long> ids = new ArrayList<>();
        for (Event event : SseCapture.dataEvents(subscription.emitter())) {
            if ("messages".equals(event.name())) {
                messageItems(event).forEach(item -> ids.add(((Number) item.get("msgId")).longValue()));
            }
        }
        return ids;
    }

    // ---------- 86: assign·latest·그룹 없음 ----------

    @Test
    @DisplayName("[CHAT-GC-86] 게이트웨이는 기동 시 latest 부터 읽는다 — 기동 전에 produce 된 메시지는 SSE 로 나가지 않고, 기동 뒤 메시지만 나간다")
    void startsFromLatest_messagesBeforeStartAreNotDelivered() throws Exception {
        String room = "R-" + UUID.randomUUID();
        long before = sendMessage(room, 1L, "재시작 사이에 온 메시지");
        Stack stack = newStack();
        ChatSubscription listener = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        long after = sendMessage(room, 1L, "기동 뒤 메시지");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedMsgIds(listener)).contains(after));
        assertThat(receivedMsgIds(listener)).doesNotContain(before);
    }

    @Test
    @DisplayName("[CHAT-GC-86] 게이트웨이는 브로커에 컨슈머 그룹을 만들지 않고 오프셋을 커밋하지 않는다")
    void createsNoConsumerGroup() throws Exception {
        Stack stack = newStack();
        stack.start();
        awaitReady(stack);

        try (Admin admin = Containers.admin()) {
            List<String> groups = admin.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId).toList();
            // 공유 브로커라 history-writer 그룹은 다른 테스트가 만들었을 수 있다. 그 밖의 그룹은 없어야 한다.
            assertThat(groups).allMatch("chat-history-writer"::equals);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-86] 파드 두 개가 같은 메시지를 각자 받아 자기 구독자에게만 보낸다")
    void twoGateways_eachDeliverToOwnSubscribers() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack podA = newStack();
        Stack podB = newStack();
        ChatSubscription onA = podA.registry.register(room, 2L, false);
        ChatSubscription onB = podB.registry.register(room, 3L, false);
        podA.start();
        podB.start();
        awaitReady(podA);
        awaitReady(podB);

        long offset = sendMessage(room, 1L, "안녕");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(receivedMsgIds(onA)).containsExactly(offset);
            assertThat(receivedMsgIds(onB)).containsExactly(offset);
        });
    }

    // ---------- 87·88·14: 프레임 내용 ----------

    @Test
    @DisplayName("[CHAT-GC-87] 실 Kafka 에서 받은 레코드의 offset 이 SSE id: 와 msgId 가 되고, 항목은 6필드이며 발신자 id 는 없다")
    void deliveredFrame_usesRecordOffsetAsIdAndMsgId() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription listener = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        long offset = sendMessage(room, 1L, "오늘 이긴다");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(SseCapture.dataEvents(listener.emitter())).isNotEmpty());
        Event event = SseCapture.dataEvents(listener.emitter()).get(0);
        assertThat(event.name()).isEqualTo("messages");
        assertThat(event.id()).isEqualTo(String.valueOf(offset));
        Map<String, Object> item = messageItems(event).get(0);
        assertThat(item.keySet()).containsExactlyInAnyOrder("msgId", "content", "senderNickname", "teamCode", "profileImgUrl", "sentAt");
        assertThat(((Number) item.get("msgId")).longValue()).isEqualTo(offset);
        assertThat(item.get("content")).isEqualTo("오늘 이긴다");
        assertThat(event.data()).doesNotContain("senderId");
    }

    @Test
    @DisplayName("[CHAT-GC-67] 발신자 본인의 스트림에는 자기 메시지가 오지 않고, 같은 방의 다른 구독자에게만 간다")
    void sender_doesNotReceiveOwnMessage() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription sender = stack.registry.register(room, 1L, false);
        ChatSubscription other = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        long offset = sendMessage(room, 1L, "내가 보냄");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedMsgIds(other)).containsExactly(offset));
        Thread.sleep(500);
        assertThat(SseCapture.dataEvents(sender.emitter())).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-87] 한 방은 한 파티션이라 연속 produce 한 메시지는 offset 오름차순으로 전달된다")
    void messagesInSameRoom_areDeliveredInOffsetOrder() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription listener = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        List<Long> sent = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sent.add(sendMessage(room, 1L, "m" + i));
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(receivedMsgIds(listener)).hasSize(50));
        assertThat(receivedMsgIds(listener)).isEqualTo(sent);
    }

    // ---------- 91: 툼스톤 ----------

    @Test
    @DisplayName("[CHAT-GC-91] chat-control 의 blind 툼스톤은 그 방 구독자 전원(발신자 포함)에게 id 없는 deleted 이벤트로 간다")
    void blindTombstone_isDeliveredAsDeletedToEveryone() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription author = stack.registry.register(room, 1L, false);
        ChatSubscription other = stack.registry.register(room, 2L, false);
        ChatSubscription elsewhere = stack.registry.register("ELSEWHERE", 3L, false);
        stack.start();
        awaitReady(stack);

        sendRaw("chat-control", room, CODEC.write(BlindTombstone.of(room, 4200L)));

        for (ChatSubscription s : List.of(author, other)) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(SseCapture.dataEvents(s.emitter())).hasSize(1));
            Event event = SseCapture.dataEvents(s.emitter()).get(0);
            assertThat(event.name()).isEqualTo("deleted");
            assertThat(event.id()).isNull();
            assertThat(event.data()).isEqualTo("{\"msgId\":4200}");
        }
        assertThat(SseCapture.dataEvents(elsewhere.emitter())).isEmpty();
    }

    // ---------- 92·35: 종료 명령 ----------

    @Test
    @DisplayName("[CHAT-GC-92] 다른 인스턴스가 낸 축출 명령은 그 사용자의 구독을 닫고, 명령 내용이 어떤 구독자에게도 data: 로 새지 않는다")
    void remoteEvictCommand_closesUserAndNeverLeaksAsData() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription target = stack.registry.register(room, 1L, false);
        ChatSubscription bystander = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        sendRaw("chat-control", "1", CODEC.write(SubscriptionCloseCommand.evict(1L, "some-other-pod")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stack.registry.subscriptions(room)).containsExactly(bystander));
        Thread.sleep(300);
        for (ChatSubscription s : List.of(target, bystander)) {
            assertThat(SseCapture.events(s.emitter()))
                    .noneMatch(e -> e.data() != null && e.data().contains("subscription-close"));
            assertThat(SseCapture.dataEvents(s.emitter())).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-35] 자기 인스턴스가 발행한 축출 명령이 Kafka 를 돌아 되돌아와도 방금 연 새 구독은 끊기지 않고 이후 메시지도 받는다")
    void ownEvictCommandEchoedViaKafka_keepsNewSubscriptionAlive() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription subscription = stack.registry.register(room, 1L, false);
        stack.start();
        awaitReady(stack);

        sendRaw("chat-control", "1", CODEC.write(SubscriptionCloseCommand.evict(1L, stack.registry.instanceId())));
        sendRaw("chat-control", "1", CODEC.write(SubscriptionCloseCommand.leave(1L, stack.registry.instanceId(), room)));
        Thread.sleep(1500); // 되돌아올 시간
        long offset = sendMessage(room, 2L, "되돌아온 명령 뒤의 메시지");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedMsgIds(subscription)).containsExactly(offset));
        assertThat(stack.registry.subscriptions(room)).containsExactly(subscription);
    }

    @Test
    @DisplayName("[CHAT-GC-49] 다른 파드에서 발행한 퇴장 명령(allRooms=false)은 이 파드의 그 방 구독만 닫는다")
    void remoteLeaveCommand_closesThatRoomOnly() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        stack.registry.register(room, 1L, false);
        ChatSubscription otherUser = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        sendRaw("chat-control", "1", CODEC.write(SubscriptionCloseCommand.leave(1L, "pod-b", room)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(stack.registry.subscriptions(room)).containsExactly(otherUser));
    }

    // ---------- 견고성 ----------

    @Test
    @DisplayName("[CHAT-GC-86] 읽을 수 없는 레코드(깨진 JSON, 알 수 없는 제어 type)는 건너뛰고 컨슈머는 계속 다음 메시지를 전달한다")
    void malformedRecords_areSkipped() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription listener = stack.registry.register(room, 2L, false);
        stack.start();
        awaitReady(stack);

        sendRaw("chat-messages", room, "{not json");
        sendRaw("chat-control", room, "{\"type\":\"mystery\"}");
        sendRaw("chat-control", room, "garbage");
        long offset = sendMessage(room, 1L, "깨진 레코드 뒤");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(receivedMsgIds(listener)).containsExactly(offset));
        assertThat(SseCapture.dataEvents(listener.emitter())).hasSize(1);
    }

    @Test
    @DisplayName("[CHAT-GC-89] 쓰기가 막힌 구독자가 있어도 같은 파드의 컨슈머는 계속 소비한다 — 정상 구독자는 대량 메시지를 모두 받는다")
    void slowSubscriber_doesNotStallConsumption() throws Exception {
        String room = "R-" + UUID.randomUUID();
        Stack stack = newStack();
        ChatSubscription slow = stack.registry.register(room, 1L, false);
        ChatSubscription healthy = stack.registry.register(room, 2L, false);
        // 느린 구독자: writeLock 을 쥐어 send/complete 가 멈추게 한다
        var field = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class.getDeclaredField("writeLock");
        field.setAccessible(true);
        var lock = (java.util.concurrent.locks.Lock) field.get(slow.emitter());
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            lock.lock();
            held.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        blocker.setDaemon(true);
        blocker.start();
        held.await();
        stack.start();
        awaitReady(stack);

        List<Long> sent = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sent.add(sendMessage(room, 9L, "m" + i));
        }

        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(receivedMsgIds(healthy)).isEqualTo(sent));
        } finally {
            release.countDown();
        }
    }

    // ---------- 9: 토픽이 없는 브로커 ----------

    @Test
    @DisplayName("[CHAT-GC-9] 토픽이 없는 브로커에 기동해도 앱은 토픽을 만들지 않고 기동을 막지도 않으며, 토픽이 생기면 소비를 시작한다")
    void brokerWithoutTopics_gatewayDoesNotCreateTopicsAndPicksUpLater() throws Exception {
        try (KafkaContainer fresh = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")) {
            fresh.start();
            String bootstrap = fresh.getBootstrapServers();
            Stack stack = new Stack(bootstrap);
            stacks.add(stack);
            String room = "R-" + UUID.randomUUID();
            ChatSubscription listener = stack.registry.register(room, 2L, false);

            stack.start();
            assertThat(stack.consumer.isRunning()).isTrue();
            Thread.sleep(4000);

            Properties adminProps = new Properties();
            adminProps.put("bootstrap.servers", bootstrap);
            try (Admin admin = Admin.create(adminProps); KafkaProducer<String, String> freshProducer = newProducer(bootstrap)) {
                assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain("chat-messages", "chat-control");

                admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic("chat-messages", 1, (short) 1),
                        new org.apache.kafka.clients.admin.NewTopic("chat-control", 1, (short) 1))).all().get(20, TimeUnit.SECONDS);

                await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> {
                    freshProducer.send(new ProducerRecord<>("chat-messages", room,
                            CODEC.write(payload(room, 1L, "토픽 생성 뒤")))).get(10, TimeUnit.SECONDS);
                    return !SseCapture.dataEvents(listener.emitter()).isEmpty();
                });
            }
        }
    }
}
