package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 앱 전체(HTTP → Redis → Kafka)를 통과하는 전송·방 경로. 요구사항 인수 기준을 실제 HTTP 응답·Redis 키·Kafka 레코드로 확인한다.
 */
class ChatSendFlowIT extends E2eSupport {

    @Autowired
    private RoomLifecycleJob lifecycleJob;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    // ---------- 경로·인증 (1, 10, 12) ----------

    @Test
    @DisplayName("[CHAT-GC-1] 외부 경로는 /chat/rooms/** 이다 — 접두사 없는 /rooms 는 404 이고, 미인증 /chat/rooms 는 RestAuthenticationEntryPoint 본문의 401 이다")
    void contextPath_chatPrefix() {
        E2eHttp.Resp unauthenticated = http.get("/chat/rooms", null);
        E2eHttp.Resp noPrefix = http.get("/rooms", user().token());

        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(unauthenticated.json().get("success").asBoolean()).isFalse();
        assertThat(unauthenticated.json().get("message").asString()).isEqualTo("인증이 필요합니다.");
        assertThat(noPrefix.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[CHAT-GC-10] 미인증 GET /chat/actuator/health 는 200 이고, 미인증 DELETE /chat/rooms/x/subscribe 는 401 이다")
    void health_isPublic_othersRequireAuth() {
        E2eHttp.Resp health = http.get("/chat/actuator/health", null);

        assertThat(health.status()).isEqualTo(200);
        assertThat(health.json().get("status").asString()).isEqualTo("UP");
        assertThat(http.delete("/chat/rooms/x/subscribe", null).status()).isEqualTo(401);
        assertThat(http.get("/chat/actuator/metrics", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("[CHAT-GC-19] 기동 직후 방 수명 작업이 실행되어 오늘 경기 방이 만들어진다 — 같은 작업을 다시 돌려도 메타가 리셋되지 않는다(NX)")
    void lifecycleJob_createsTodaysRooms() {
        Game g = com.skhynix.chat.support.ChatFixtures.game("E2E-JOB-" + System.nanoTime(),
                clock.today().atTime(19, 0), "SCHEDULED");
        org.mockito.BDDMockito.given(gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of(g));
        String roomId = g.getNaverGameId();

        lifecycleJob.run();
        redis.opsForValue().set("chat:room:" + roomId, "custom", Duration.ofSeconds(900));
        lifecycleJob.run();

        assertThat(redis.opsForValue().get("chat:room:" + roomId)).isEqualTo("custom");
        assertThat(redis.opsForSet().isMember("chat:rooms:" + com.skhynix.chat.shared.ChatClock.roomDate(clock.today()), roomId)).isTrue();
    }

    // ---------- 방 목록·상세 (15, 24~29, 107) ----------

    @Test
    @DisplayName("[CHAT-GC-26] 방 목록은 200 이고 games 만 읽는다 — 목록 호출이 Redis 방 키를 만들지 않는다. 필드는 7개다")
    void roomList_readsOnlyGames() {
        String roomId = room();
        Game g = gameRepository.findByNaverGameId(roomId).orElseThrow();
        org.mockito.BDDMockito.given(gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of(g));
        TestUser u = user();

        E2eHttp.Resp resp = http.get("/chat/rooms", u.token());

        assertThat(resp.status()).isEqualTo(200);
        JsonNode first = resp.json().get("data").get(0);
        assertThat(first.get("gameId").asString()).isEqualTo(roomId);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder(
                "gameId", "homeTeam", "homeTeamId", "awayTeam", "awayTeamId", "gameDate", "gameState");
        assertThat(redis.hasKey("chat:room:" + roomId)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-21] 경기 없는 날 목록은 200 빈 배열이고, 임의 gameId 의 상세·구독·전송·히스토리·신고는 전부 404 이다")
    void noGamesDay_listEmpty_everythingElse404() {
        anyGame();
        TestUser u = user();

        assertThat(http.get("/chat/rooms", u.token()).json().get("data")).isEmpty();
        assertThat(http.get("/chat/rooms/nothing", u.token()).status()).isEqualTo(404);
        assertThat(send(u, "nothing", "안녕", newClientMsgId()).status()).isEqualTo(404);
        assertThat(http.get("/chat/rooms/nothing/messages", u.token()).status()).isEqualTo(404);
        assertThat(http.post("/chat/rooms/nothing/messages/1/report", u.token(), null).status()).isEqualTo(404);
        try (E2eHttp.SseStream stream = http.subscribe("/chat/rooms/nothing/subscribe", u.token(), null,
                "application/json, text/event-stream")) {
            await().atMost(Duration.ofSeconds(5)).until(() -> stream.status() != -1);
            assertThat(stream.status()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-31] 없는 방 구독은 Accept: text/event-stream 만 보내는 SSE 클라이언트(fetch 폴리필)에게도 404 JSON 이어야 한다")
    void subscribe_unknownRoom_withEventStreamOnlyAccept_is404() {
        anyGame();
        TestUser u = user();

        try (E2eHttp.SseStream stream = http.subscribe("/chat/rooms/nothing/subscribe", u.token(), null)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> stream.status() != -1);
            assertThat(stream.status()).as("본문: " + stream.errorBody()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-107] 메타가 없는 오늘 경기 방을 상세 조회하면 200 이고 그 자리에서 지연 생성된다 — 메타 TTL ≤ 다음 자정까지 남은 초, 방 집합에 등록")
    void roomDetail_lazilyCreatesRoom() {
        String roomId = room();

        E2eHttp.Resp resp = http.get("/chat/rooms/" + roomId, user().token());

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.json().get("data").get("gameId").asString()).isEqualTo(roomId);
        assertThat(resp.json().get("data").get("gameState").asString()).isEqualTo("SCHEDULED");
        Long ttl = redis.getExpire("chat:room:" + roomId);
        assertThat(ttl).isBetween(1L, clock.secondsUntilNextMidnight() + 1);
        assertThat(redis.opsForSet().isMember("chat:rooms:" + com.skhynix.chat.shared.ChatClock.roomDate(clock.today()), roomId)).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-107] 히스토리·신고는 지연 생성을 하지 않는다 — 오늘 경기여도 메타가 없으면 404 이고 메타가 생기지 않는다")
    void historyAndReport_doNotLazilyCreate() {
        String roomId = room();
        TestUser u = user();

        assertThat(http.get("/chat/rooms/" + roomId + "/messages", u.token()).status()).isEqualTo(404);
        assertThat(http.post("/chat/rooms/" + roomId + "/messages/1/report", u.token(), null).status()).isEqualTo(404);

        assertThat(redis.hasKey("chat:room:" + roomId)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-28] 어제 경기는 404 CHATROOM_NOT_FOUND 이고 방이 만들어지지 않는다")
    void roomDetail_yesterdayGame_is404() {
        String roomId = "E2E-OLD-" + System.nanoTime();
        Game yesterday = com.skhynix.chat.support.ChatFixtures.game(roomId, clock.today().minusDays(1).atTime(18, 30), "FINISHED");
        org.mockito.BDDMockito.given(gameRepository.findByNaverGameId(roomId)).willReturn(java.util.Optional.of(yesterday));

        E2eHttp.Resp resp = http.get("/chat/rooms/" + roomId, user().token());

        assertThat(resp.status()).isEqualTo(404);
        assertThat(resp.json().get("message").asString()).isEqualTo("존재하지 않는 채팅방입니다.");
        assertThat(redis.hasKey("chat:room:" + roomId)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-23] 종료(FINISHED)·취소(CANCELED) 경기의 방에도 자정 전까지 전송 202 가 나간다")
    void finishedGame_stillAcceptsMessages() {
        String roomId = "E2E-FIN-" + System.nanoTime();
        Game finished = com.skhynix.chat.support.ChatFixtures.game(roomId, clock.today().atTime(13, 0), "FINISHED");
        org.mockito.BDDMockito.given(gameRepository.findByNaverGameId(roomId)).willReturn(java.util.Optional.of(finished));

        assertThat(send(user(), roomId, "경기 끝났다", newClientMsgId()).status()).isEqualTo(202);
    }

    // ---------- 전송 해피 패스 (13, 14, 50, 63, 97, 106) ----------

    @Test
    @DisplayName("[CHAT-GC-50] 전송은 202 {gameId, msgId, content} 이고 msgId 는 chat-messages 레코드의 실제 파티션 오프셋이다. MySQL 쓰기·chat:seq 키는 없다")
    void send_happyPath() {
        String roomId = room();
        TestUser sender = user();

        E2eHttp.Resp resp = send(sender, roomId, "오늘 이긴다", newClientMsgId());

        assertThat(resp.status()).isEqualTo(202);
        JsonNode data = resp.json().get("data");
        assertThat(resp.json().get("success").asBoolean()).isTrue();
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("gameId", "msgId", "content");
        assertThat(data.get("gameId").asString()).isEqualTo(roomId);
        assertThat(data.get("content").asString()).isEqualTo("오늘 이긴다");

        List<ConsumerRecord<String, String>> records = KafkaReads.byKey("chat-messages", roomId, 1, Duration.ofSeconds(15));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).offset()).isEqualTo(data.get("msgId").asLong());
        verify(userAccountRepository, never()).save(any());
        assertThat(redis.keys("chat:seq:*")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-63] Kafka 레코드 value 는 7개 필드(msgId 없음)이고 teamCode·profileImgUrl 은 전송 시점 스냅샷, sentAt 은 +09:00 오프셋 ISO-8601 이다")
    void send_kafkaRecordHasSevenFieldSnapshot() {
        String roomId = room();
        TestUser sender = user("OB", "user-profile-img/u.jpg");
        sendOk(sender, roomId, "안녕");

        ConsumerRecord<String, String> record = KafkaReads.byKey("chat-messages", roomId, 1, Duration.ofSeconds(15)).get(0);

        JsonNode value = E2eHttp.MAPPER.readTree(record.value());
        assertThat(value.propertyNames()).containsExactlyInAnyOrder(
                "gameId", "senderId", "senderNickname", "teamCode", "profileImgUrl", "content", "sentAt");
        assertThat(value.get("senderId").asLong()).isEqualTo(sender.id());
        assertThat(value.get("senderNickname").asString()).isEqualTo("닉네임" + sender.id());
        assertThat(value.get("teamCode").asString()).isEqualTo("OB");
        assertThat(value.get("profileImgUrl").asString()).isEqualTo("user-profile-img/u.jpg");
        assertThat(value.get("sentAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-13] 응원 구단이 없는 계정도 202 이고 레코드의 teamCode·profileImgUrl 은 null 이다")
    void send_userWithoutSupportTeam_isAccepted() {
        String roomId = room();
        TestUser noTeam = user(null, null);

        assertThat(send(noTeam, roomId, "구단 없음", newClientMsgId()).status()).isEqualTo(202);

        JsonNode value = E2eHttp.MAPPER.readTree(
                KafkaReads.byKey("chat-messages", roomId, 1, Duration.ofSeconds(15)).get(0).value());
        assertThat(value.get("teamCode").isNull()).isTrue();
        assertThat(value.get("profileImgUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-97] history-writer 가 레코드를 Stream chat:game:{gameId} 에 {offset}-0 엔트리로 적재하고 다음 자정 TTL 을 건다")
    void send_historyWriterCopiesRecordToStream() {
        String roomId = room();
        TestUser sender = user();
        long msgId = msgIdOf(sendOk(sender, roomId, "히스토리 사본"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var entries = redis.opsForStream().range("chat:game:" + roomId,
                    org.springframework.data.domain.Range.unbounded());
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getId().getValue()).isEqualTo(msgId + "-1");
            assertThat(entries.get(0).getValue()).containsEntry("content", "히스토리 사본")
                    .containsEntry("senderId", String.valueOf(sender.id()));
        });
        assertThat(redis.getExpire("chat:game:" + roomId)).isBetween(1L, clock.secondsUntilNextMidnight() + 1);
    }

    // ---------- 마스킹 (54, 55) ----------

    @Test
    @DisplayName("[CHAT-GC-54] 욕설은 같은 길이의 * 로 바뀐 content 가 202 본문과 Kafka 레코드에 실리고 전송은 거절되지 않는다(55)")
    void send_profanityIsMasked() {
        String roomId = room();
        TestUser sender = user();

        E2eHttp.Resp resp = send(sender, roomId, "시발 오늘", newClientMsgId());

        assertThat(resp.status()).isEqualTo(202);
        assertThat(resp.json().get("data").get("content").asString()).isEqualTo("** 오늘");
        JsonNode value = E2eHttp.MAPPER.readTree(
                KafkaReads.byKey("chat-messages", roomId, 1, Duration.ofSeconds(15)).get(0).value());
        assertThat(value.get("content").asString()).isEqualTo("** 오늘");
        assertThat(value.toString()).doesNotContain("시발");
        assertThat(send(sender, roomId, "시발", newClientMsgId()).json().get("data").get("content").asString()).isEqualTo("**");
    }

    // ---------- 판정 순서 (51~53, 58) ----------

    @Test
    @DisplayName("[CHAT-GC-51] 없는 방 + 빈 content 는 404 이다(400 이 아니다)")
    void send_unknownRoomAndBlankContent_is404() {
        anyGame();

        assertThat(send(user(), "nope", "", newClientMsgId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[CHAT-GC-51] 있는 방 + 501자는 400 이고 속도 제한 카운터(chat:rate)·dedup 키가 생기지 않는다")
    void send_tooLong_is400AndTouchesNoRateOrDedupKey() {
        String roomId = room();
        TestUser u = user();
        String clientMsgId = newClientMsgId();

        E2eHttp.Resp resp = send(u, roomId, "가".repeat(501), clientMsgId);

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.json().get("data").get("content").asString()).isNotBlank();
        assertThat(redis.hasKey("chat:rate:" + u.id())).isFalse();
        assertThat(redis.hasKey("chat:dedup:" + roomId + ":" + clientMsgId)).isFalse();
        assertThat(KafkaReads.count("chat-messages", roomId)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-52] 공백뿐인 content 는 400 + data.content 이고, 정확히 500자는 202 이다")
    void send_blankIs400_500CharsIs202() {
        String roomId = room();
        TestUser u = user();

        E2eHttp.Resp blank = send(u, roomId, "   ", newClientMsgId());

        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.json().get("data").has("content")).isTrue();
        assertThat(send(u, roomId, "가".repeat(500), newClientMsgId()).status()).isEqualTo(202);
    }

    @Test
    @DisplayName("[CHAT-GC-53] clientMsgId 가 누락되거나 UUID 형식이 아니면 400 + data.clientMsgId 이다")
    void send_invalidClientMsgId_is400() {
        String roomId = room();
        TestUser u = user();

        E2eHttp.Resp missing = http.post("/chat/rooms/" + roomId + "/messages", u.token(), "{\"content\":\"안녕\"}");
        E2eHttp.Resp malformed = send(u, roomId, "안녕", "abc");

        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().get("data").has("clientMsgId")).isTrue();
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.json().get("data").has("clientMsgId")).isTrue();
        assertThat(KafkaReads.count("chat-messages", roomId)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-58] 같은 초에 4번째 전송은 429 이고 그 요청의 dedup 키는 생성되지 않는다. 카운터 키는 1초 TTL 이다")
    void send_fourthWithinSecond_is429() {
        String roomId = room();
        TestUser u = user();
        for (int i = 0; i < 3; i++) {
            assertThat(send(u, roomId, "m" + i, newClientMsgId()).status()).isEqualTo(202);
        }
        String fourth = newClientMsgId();

        E2eHttp.Resp resp = send(u, roomId, "m3", fourth);

        assertThat(resp.status()).isEqualTo(429);
        assertThat(resp.json().get("message").asString()).isEqualTo("메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(redis.hasKey("chat:dedup:" + roomId + ":" + fourth)).isFalse();
        assertThat(redis.getExpire("chat:rate:" + u.id(), java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, 1000L);
    }

    @Test
    @DisplayName("[CHAT-GC-58] 방이 달라도 같은 사용자는 한 창을 공유한다")
    void send_rateWindowSharedAcrossRooms() {
        String roomA = room();
        String roomB = room();
        TestUser u = user();
        send(u, roomA, "a1", newClientMsgId());
        send(u, roomA, "a2", newClientMsgId());
        send(u, roomB, "b1", newClientMsgId());

        assertThat(send(u, roomB, "b2", newClientMsgId()).status()).isEqualTo(429);
    }

    // ---------- dedup (60, 61, 105, 106) ----------

    @Test
    @DisplayName("[CHAT-GC-106] 같은 clientMsgId 재요청은 첫 응답과 같은 본문(msgId·content)을 재반환하고 Kafka 레코드는 1건이다. 확정값 JSON 과 TTL 이 남는다")
    void send_sameClientMsgIdTwice_replaysSameBody() {
        String roomId = room();
        TestUser u = user();
        String clientMsgId = newClientMsgId();

        E2eHttp.Resp first = send(u, roomId, "시발 오늘", clientMsgId);
        E2eHttp.Resp second = send(u, roomId, "시발 오늘", clientMsgId);

        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(202);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(KafkaReads.count("chat-messages", roomId)).isEqualTo(1);
        String stored = redis.opsForValue().get("chat:dedup:" + roomId + ":" + clientMsgId);
        assertThat(E2eHttp.MAPPER.readTree(stored).get("msgId").asLong()).isEqualTo(msgIdOf(first));
        assertThat(E2eHttp.MAPPER.readTree(stored).get("content").asString()).isEqualTo("** 오늘");
        assertThat(redis.getExpire("chat:dedup:" + roomId + ":" + clientMsgId)).isBetween(1L, 120L);
    }

    @Test
    @DisplayName("[CHAT-GC-105] 키가 PENDING 이면 같은 clientMsgId 재요청은 409 CHAT_MESSAGE_IN_FLIGHT 이고 Kafka 레코드가 생기지 않는다")
    void send_whilePending_is409() {
        String roomId = room();
        TestUser u = user();
        openRoom(u, roomId);
        String clientMsgId = newClientMsgId();
        redis.opsForValue().set("chat:dedup:" + roomId + ":" + clientMsgId, "PENDING", Duration.ofSeconds(60));

        E2eHttp.Resp resp = send(u, roomId, "안녕", clientMsgId);

        assertThat(resp.status()).isEqualTo(409);
        assertThat(resp.json().get("message").asString()).isEqualTo("같은 메시지를 처리하고 있습니다. 잠시 후 다시 시도해 주세요.");
        assertThat(KafkaReads.count("chat-messages", roomId)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-60] 첫 요청 처리 중에는 키가 PENDING 으로 선점된다 — 확정 전 값은 PENDING, 확정 뒤에는 JSON")
    void send_dedupKeyLifecycle() {
        String roomId = room();
        TestUser u = user();
        String clientMsgId = newClientMsgId();

        send(u, roomId, "안녕", clientMsgId);

        String key = "chat:dedup:" + roomId + ":" + clientMsgId;
        assertThat(redis.opsForValue().get(key)).startsWith("{\"msgId\":");
        redis.delete(key);
        assertThat(send(u, roomId, "안녕", clientMsgId).status()).isEqualTo(202);
        assertThat(KafkaReads.count("chat-messages", roomId)).as("키가 사라지면(121초 뒤와 같은 상황) 새 produce 이다").isEqualTo(2);
    }

    @Test
    @DisplayName("[CHAT-GC-61] 같은 clientMsgId 재시도 4회도 속도 제한에 계수되어 4번째는 429 이다(dedup 판정보다 429 가 먼저)")
    void send_retriesCountTowardRateLimit() {
        String roomId = room();
        TestUser u = user();
        String clientMsgId = newClientMsgId();
        send(u, roomId, "x", clientMsgId);
        send(u, roomId, "x", clientMsgId);
        send(u, roomId, "x", clientMsgId);

        assertThat(send(u, roomId, "x", clientMsgId).status()).isEqualTo(429);
    }

    // ---------- 프로듀서·토픽 설정 (9, 108) ----------

    @Test
    @DisplayName("[CHAT-GC-108] 실제 KafkaTemplate 의 프로듀서 설정은 acks=all, enable.idempotence=true 이다")
    void producerFactory_isIdempotentAcksAll() {
        Map<String, Object> config = kafkaTemplate.getProducerFactory().getConfigurationProperties();

        assertThat(String.valueOf(config.get("acks"))).isEqualTo("all");
        assertThat(String.valueOf(config.get("enable.idempotence"))).isEqualTo("true");
    }

    @Test
    @DisplayName("[CHAT-GC-99] 거부 카운터는 GET /chat/actuator/metrics/chat.history.xadd.rejected 로 읽힌다(인증 필요) — 노출 목록은 health,metrics 뿐이다")
    void metrics_xaddRejectedCounterIsReadable() {
        TestUser u = user();

        E2eHttp.Resp authenticated = http.get("/chat/actuator/metrics/chat.history.xadd.rejected", u.token());
        E2eHttp.Resp anonymous = http.get("/chat/actuator/metrics/chat.history.xadd.rejected", null);
        E2eHttp.Resp env = http.get("/chat/actuator/env", u.token());

        assertThat(authenticated.status()).isEqualTo(200);
        assertThat(authenticated.json().get("name").asString()).isEqualTo("chat.history.xadd.rejected");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(env.status()).as("env 는 노출하지 않는다").isEqualTo(404);
    }

    @Test
    @DisplayName("[CHAT-GC-101] history-writer 컨슈머 랙 게이지 kafka.consumer.fetch.manager.records.lag.max 가 chat-history-writer 클라이언트로 노출된다")
    void metrics_historyWriterLagGaugeIsExposed() {
        TestUser u = user();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            E2eHttp.Resp resp = http.get("/chat/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max", u.token());
            assertThat(resp.status()).isEqualTo(200);
            E2eHttp.Resp tagged = http.get("/chat/actuator/metrics/kafka.consumer.fetch.manager.records.lag.max", u.token());
            assertThat(tagged.json().get("availableTags").toString()).contains("client.id");
            assertThat(tagged.json().get("availableTags").toString()).contains("chat-history-writer");
        });
    }

    @Test
    @DisplayName("[CHAT-GC-96] 브로커의 컨슈머 그룹은 chat-history-writer 하나뿐이다 — 게이트웨이 컨슈머는 그룹 없이 assign 한다(CHAT-GC-4·86)")
    void brokerHasOnlyHistoryWriterGroup() throws Exception {
        try (var admin = Containers.admin()) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var groups = admin.listConsumerGroups().all().get(10, java.util.concurrent.TimeUnit.SECONDS).stream()
                        .map(g -> g.groupId()).toList();
                assertThat(groups).containsExactly("chat-history-writer");
            });
        }
    }

    @Test
    @DisplayName("[CHAT-GC-15] 방 식별자는 games.naver_game_id 문자열이다 — 내부 PK(숫자)로는 404")
    void roomId_isNaverGameIdNotInternalPk() {
        String roomId = room();
        TestUser u = user();

        assertThat(http.get("/chat/rooms/" + roomId, u.token()).status()).isEqualTo(200);
        assertThat(http.get("/chat/rooms/12345", u.token()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("[CHAT-GC-9] 앱이 떠 있어도 브로커의 토픽 목록은 테스트가 만든 chat-messages·chat-control 뿐이다(앱은 토픽을 만들지 않는다)")
    void app_doesNotCreateTopics() throws Exception {
        try (var admin = Containers.admin()) {
            Set<String> topics = admin.listTopics().names().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(topics).contains("chat-messages", "chat-control");
            assertThat(topics).allMatch(t -> t.equals("chat-messages") || t.equals("chat-control") || t.startsWith("__"));
        }
    }
}
