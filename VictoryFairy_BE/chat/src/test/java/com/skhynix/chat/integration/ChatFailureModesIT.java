package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 장애 시 동작(요구사항 L절의 교차 참조 + 개별 ID). Redis·Kafka 컨테이너를 잠시 멈췄다(docker pause) 되돌린다.
 * 항상 finally 에서 되돌리며, 공유 컨테이너를 쓰는 다른 테스트에 영향이 없도록 마지막에 실행한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatFailureModesIT extends E2eSupport {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static void pause(com.github.dockerjava.api.DockerClient docker, String id) {
        docker.pauseContainerCmd(id).exec();
    }

    private static void unpause(com.github.dockerjava.api.DockerClient docker, String id) {
        docker.unpauseContainerCmd(id).exec();
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
    @Order(1)
    @DisplayName("[CHAT-GC-62] Kafka 가 응답하지 않으면 전송은 503 이고 방금 선점한 dedup 키가 지워진다. 신고(82)도 503, 퇴장(48)은 로컬 종료만 하고 200, 브로커가 돌아오면 같은 clientMsgId 가 409 가 아니라 202(새 offset)다")
    void kafkaDown_sendIs503AndDedupKeyDeleted_thenRetrySucceeds() throws Exception {
        String room = room();
        TestUser sender = user();
        TestUser reporter = user();
        openRoom(sender, room);
        // 신고 대상 엔트리(발신자는 다른 사용자)
        seedStreamEntry(room, 770_000_001L, sender.id(), "신고 대상");
        String clientMsgId = newClientMsgId();
        long firstOffset = msgIdOf(sendOk(user(), room, "장애 전 정상 전송"));
        var docker = Containers.kafka().getDockerClient();
        String containerId = Containers.kafka().getContainerId();

        pause(docker, containerId);
        try {
            long start = System.nanoTime();
            E2eHttp.Resp down = send(sender, room, "브로커 정지 중", clientMsgId);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(down.status()).isEqualTo(503);
            assertThat(down.json().get("message").asString()).isEqualTo("채팅 서버가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.");
            assertThat(elapsedMs).as("send-timeout(2s) 근방에서 끝나야 한다").isLessThan(15_000);
            assertThat(redis.hasKey("chat:dedup:" + room + ":" + clientMsgId)).as("503 뒤 dedup 키는 남지 않는다").isFalse();

            // 82: 툼스톤 발행 실패는 200 으로 삼키지 않는다
            assertThat(http.post("/chat/rooms/" + room + "/messages/770000001/report", reporter.token(), null).status())
                    .isEqualTo(503);
            // 48: 종료 명령 발행이 실패해도 퇴장은 200
            assertThat(http.delete("/chat/rooms/" + room + "/subscribe", reporter.token()).status()).isEqualTo(200);
        } finally {
            unpause(docker, containerId);
        }

        await().atMost(WAIT).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            E2eHttp.Resp retry = send(sender, room, "브로커 복구 뒤 같은 clientMsgId", clientMsgId);
            assertThat(retry.status()).as("복구 뒤 재시도: " + retry.body()).isEqualTo(202);
            assertThat(msgIdOf(retry)).isGreaterThan(firstOffset);
        });
    }

    @Test
    @Order(2)
    @DisplayName("[CHAT-GC-48] Kafka 가 멈춘 상태에서도 구독은 열린다 — 다른 파드로 보낼 축출 명령 발행이 실패해도 구독 요청은 실패하지 않는다")
    void kafkaDown_subscribeStillOpens() throws Exception {
        String room = room();
        TestUser u = user();
        openRoom(u, room);
        var docker = Containers.kafka().getDockerClient();
        String containerId = Containers.kafka().getContainerId();

        pause(docker, containerId);
        try (E2eHttp.SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", u.token(), null)) {
            awaitRegistered(u, room);
            assertThat(registry.subscriptions(room)).anyMatch(sub -> sub.userAccountId() == u.id());
        } finally {
            unpause(docker, containerId);
        }
        // 다음 테스트를 위해 브로커가 응답할 때까지 기다린다
        await().atMost(WAIT).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(send(user(), room, "복구 확인", newClientMsgId()).status()).isEqualTo(202));
    }

    @Test
    @Order(10)
    @DisplayName("[CHAT-GC-29] Redis 가 멈추면 방 상세·구독·전송·히스토리·신고는 503, 방 목록은 200 이다. [CHAT-GC-95] 이미 열린 SSE 의 실시간 전달은 계속되고, [CHAT-GC-100] history-writer 는 복구 뒤 밀린 레코드를 Stream 에 따라잡는다")
    void redisDown_503ForRoomPaths_listStill200_realtimeContinues_writerCatchesUp() throws Exception {
        String room = room();
        TestUser listener = user();
        TestUser stranger = user();
        openRoom(listener, room);
        Game g = gameRepository.findByNaverGameId(room).orElseThrow();
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of(g));
        var docker = Containers.redis().getDockerClient();
        String containerId = Containers.redis().getContainerId();
        long offset;

        try (E2eHttp.SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", listener.token(), null)) {
            awaitRegistered(listener, room);
            // 게이트웨이 컨슈머가 이 방의 메시지를 받을 준비가 됐는지 확인한다(latest 정책)
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                sendOk(user(), room, "준비 확인");
                return stream.dataEvents().stream().anyMatch(e -> "messages".equals(e.name()));
            });
            int before = stream.dataEvents().size();

            pause(docker, containerId);
            try {
                assertThat(http.get("/chat/rooms/" + room, stranger.token()).status()).isEqualTo(503);
                assertThat(send(stranger, room, "안녕", newClientMsgId()).status()).isEqualTo(503);
                assertThat(http.get("/chat/rooms/" + room + "/messages", stranger.token()).status()).isEqualTo(503);
                assertThat(http.post("/chat/rooms/" + room + "/messages/1/report", stranger.token(), null).status()).isEqualTo(503);
                try (E2eHttp.SseStream denied = http.subscribe("/chat/rooms/" + room + "/subscribe", stranger.token(), null,
                        "application/json, text/event-stream")) {
                    await().atMost(Duration.ofSeconds(15)).until(() -> denied.status() != -1);
                    assertThat(denied.status()).isEqualTo(503);
                }
                E2eHttp.Resp list = http.get("/chat/rooms", stranger.token());
                assertThat(list.status()).as("목록은 Redis 를 보지 않는다").isEqualTo(200);
                assertThat(list.json().get("data")).hasSize(1);

                // 95: 이미 produce 되는 레코드의 전달은 Redis 와 무관하다
                try (KafkaProducer<String, String> producer = rawProducer()) {
                    String payload = "{\"gameId\":\"" + room + "\",\"senderId\":999999,\"senderNickname\":\"외부\","
                            + "\"teamCode\":null,\"profileImgUrl\":null,\"content\":\"Redis 정지 중에도 전달\","
                            + "\"sentAt\":\"2026-10-09T19:03:21.123+09:00\"}";
                    offset = producer.send(new ProducerRecord<>("chat-messages", room, payload)).get(10, TimeUnit.SECONDS).offset();
                }
                await().atMost(Duration.ofSeconds(20)).until(() -> stream.dataEvents().size() > before
                        && stream.dataEvents().stream().anyMatch(e -> e.data() != null && e.data().contains("Redis 정지 중에도 전달")));
            } finally {
                unpause(docker, containerId);
            }
        }

        // 100: 복구 뒤 밀린 레코드가 Stream 에 생긴다(건너뛰어 구멍을 내지 않는다)
        long expected = offset;
        await().atMost(WAIT).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            var entries = redis.opsForStream().range("chat:game:" + room, org.springframework.data.domain.Range.unbounded());
            assertThat(entries).extracting(r -> r.getId().getValue()).contains(expected + "-1");
        });
        assertThat(http.get("/chat/rooms/" + room, stranger.token()).status()).as("Redis 복구 뒤 정상").isEqualTo(200);
    }
}
