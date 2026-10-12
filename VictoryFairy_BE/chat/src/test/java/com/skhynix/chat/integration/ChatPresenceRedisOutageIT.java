package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.chat.gateway.service.PresenceTracker;
import com.skhynix.chat.integration.E2eHttp.SseStream;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 장애 시 접속 인원. 앱의 Redis 연결을 {@link RedisProxy} 로 돌려 정지(DOWN)·무응답(BLACKHOLE)·복구를 만든다.
 * 실제 Redis 데이터는 컨테이너에 그대로 있으므로 복구는 "연결만 되살아난" 상황이다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ChatPresenceRedisOutageIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static AppLauncher launch(RedisProxy proxy, String... args) throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        AppLauncher app = new AppLauncher();
        app.redisHostOverride = proxy.host();
        app.redisPortOverride = proxy.port();
        return app;
    }

    private static String room(AppLauncher app) {
        String gameId = "OUT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Game today = game(gameId, new ChatClock().today().atTime(18, 30), "SCHEDULED");
        given(app.gameRepository.findByNaverGameId(gameId)).willReturn(Optional.of(today));
        given(app.gameRepository.findWithDetailsByNaverGameId(gameId)).willReturn(Optional.of(today));
        return gameId;
    }

    private static SseStream open(AppLauncher app, long id, String token, String room) {
        SseStream stream = app.http().subscribe("/chat/rooms/" + room + "/subscribe", token, null);
        SseEmitterRegistry registry = app.context.getBean(SseEmitterRegistry.class);
        await().atMost(WAIT).until(() -> registry.subscriptions(room).stream().anyMatch(s -> id == s.userAccountId()));
        return stream;
    }

    private static List<Long> counts(SseStream stream) {
        return stream.presenceEvents().stream().map(e -> E2eHttp.MAPPER.readTree(e.data()).get("count").asLong()).toList();
    }

    private static long last(SseStream stream) {
        List<Long> counts = counts(stream);
        return counts.isEmpty() ? -1 : counts.get(counts.size() - 1);
    }

    private static long pings(SseStream stream) {
        return stream.events().stream().filter(e -> "ping".equals(e.comment())).count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static KafkaProducer<String, String> rawProducer() {
        Properties p = new Properties();
        p.put("bootstrap.servers", Containers.kafkaBootstrapServers());
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all");
        return new KafkaProducer<>(p);
    }

    @Test
    @DisplayName("[CHAT-PR-23][CHAT-PR-24][CHAT-PR-26][CHAT-PR-27] Redis 가 25초 정지된 동안 presence 프레임이 없고 스트림이 유지되며 :ping 이 오고 WARN 은 10초에 1줄(누적 건수 포함)이다. 복구되면 재시작 없이 실제 인원(1)이 나간다")
    void redisDown_noFrames_streamKept_throttledWarn_thenRecovers() throws Exception {
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            long idA = 81_001L;
            long idB = 81_002L;
            String tokenA = app.userToken(idA);
            String tokenB = app.userToken(idB);
            // 만료 창을 줄여 정지 중 만료되는 경로(복구 뒤 다시 반영)까지 짧은 시간에 본다
            app.start("--chat.presence.expire-seconds=20");
            String room = room(app);
            ListAppender<ILoggingEvent> warns = new ListAppender<>();
            warns.start();
            // 앱을 띄운 뒤에 붙여야 한다 — 부트가 기동하며 로깅을 다시 초기화한다
            ((Logger) LoggerFactory.getLogger(PresenceTracker.class)).addAppender(warns);

            try (SseStream sa = open(app, idA, tokenA, room); SseStream sb = open(app, idB, tokenB, room)) {
                await().atMost(WAIT).until(() -> last(sa) == 2 && last(sb) == 2);
                List<Long> beforeOutage = counts(sa);
                long pingsBefore = pings(sa);

                proxy.set(RedisProxy.Mode.DOWN);
                sleep(500);
                assertThat(app.http().delete("/chat/rooms/" + room + "/subscribe", tokenB).status()).isEqualTo(200);
                sleep(25_000);

                assertThat(counts(sa)).as("정지 중 입장·퇴장이 있어도 새 프레임 없음, 대체 값·null count 없음").isEqualTo(beforeOutage);
                assertThat(sa.isEnded()).as("스트림 유지").isFalse();
                assertThat(pings(sa)).as("정지 중에도 :ping(하트비트)이 계속 온다").isGreaterThan(pingsBefore);
                List<ILoggingEvent> lines = warns.list.stream().filter(e -> e.getLevel().toString().equals("WARN")).toList();
                assertThat(lines).as("presence WARN 이 있고(장애를 관찰) 25초에 4줄 이하").isNotEmpty().hasSizeLessThanOrEqualTo(4);
                assertThat(lines).allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("직전 로그 이후").contains("건"));

                proxy.set(RedisProxy.Mode.UP);

                await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                        assertThat(last(sa)).as("복구 뒤 A 가 받은 값: " + counts(sa)).isEqualTo(1L));
                assertThat(counts(sa)).as("[CHAT-PR-26][CHAT-PR-31] 복구 중 로컬 고유 계정 수(1) 미만인 0 은 나가지 않는다").doesNotContain(0L);
            } finally {
                ((Logger) LoggerFactory.getLogger(PresenceTracker.class)).detachAppender(warns);
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-24] Redis 가 응답하지 않아도(BLACKHOLE, 명령 타임아웃 2초) 하트비트 호출은 즉시 돌아오고 :ping 은 Redis 타임아웃만큼 밀리지 않으며 Kafka 경로의 messages 는 계속 온다")
    void redisHang_heartbeatAndPingNotDelayed_messagesContinue() throws Exception {
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            long idA = 81_011L;
            String tokenA = app.userToken(idA);
            app.start();
            String room = room(app);
            SseEmitterRegistry registry = app.context.getBean(SseEmitterRegistry.class);

            try (SseStream sa = open(app, idA, tokenA, room); KafkaProducer<String, String> producer = rawProducer()) {
                await().atMost(WAIT).until(() -> last(sa) == 1);

                proxy.set(RedisProxy.Mode.BLACKHOLE);
                sleep(300);
                long pingsBefore = pings(sa);

                long start = System.nanoTime();
                registry.heartbeat();
                long heartbeatMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(heartbeatMs).as("하트비트 스레드가 Redis 왕복에 붙잡히지 않는다(타임아웃 2000ms 미만)").isLessThan(500);
                await().atMost(Duration.ofMillis(1_500)).alias(":ping 이 Redis 타임아웃(2초) 전에 도착").until(() -> pings(sa) > pingsBefore);

                String payload = "{\"gameId\":\"" + room + "\",\"senderId\":999999,\"senderNickname\":\"외부\","
                        + "\"teamCode\":null,\"profileImgUrl\":null,\"content\":\"Redis 무응답 중에도 전달\","
                        + "\"sentAt\":\"2026-10-09T19:03:21.123+09:00\"}";
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                    producer.send(new ProducerRecord<>("chat-messages", room, payload)).get(10, TimeUnit.SECONDS);
                    return sa.dataEvents().stream().anyMatch(e -> e.data() != null && e.data().contains("Redis 무응답 중에도 전달"));
                });
                assertThat(sa.isEnded()).isFalse();
                assertThat(counts(sa)).as("무응답 중 새 presence 없음").containsExactly(1L);
            } finally {
                proxy.set(RedisProxy.Mode.UP);
            }
        }
    }
}
