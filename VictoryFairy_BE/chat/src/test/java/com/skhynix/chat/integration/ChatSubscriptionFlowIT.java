package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.integration.E2eHttp.SseEvent;
import com.skhynix.chat.integration.E2eHttp.SseStream;
import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SSE 구독·복구·퇴장·신고·히스토리를 실제 HTTP/SSE 로 확인한다(앱 전체 + 실 Redis + 실 Kafka).
 */
class ChatSubscriptionFlowIT extends E2eSupport {

    private static final Duration WAIT = Duration.ofSeconds(20);

    /** 구독을 열고 서버 레지스트리에 올라올 때까지 기다린다(응답 헤더 도착은 기다리지 않는다). */
    private SseStream open(TestUser user, String room, String lastEventId) {
        SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", user.token(), lastEventId);
        awaitRegistered(user, room);
        return stream;
    }

    private SseStream openOk(TestUser user, String room, String lastEventId) {
        return open(user, room, lastEventId);
    }

    private static List<SseEvent> named(SseStream stream, String name) {
        return stream.dataEvents().stream().filter(e -> name.equals(e.name())).toList();
    }

    private static JsonNode items(SseEvent event) {
        return E2eHttp.MAPPER.readTree(event.data());
    }

    private static List<Long> msgIds(SseEvent event) {
        List<Long> ids = new ArrayList<>();
        items(event).forEach(item -> ids.add(item.get("msgId").asLong()));
        return ids;
    }

    // ---------- 구독 (30) ----------

    @Test
    @DisplayName("[CHAT-GC-30] 구독은 200 text/event-stream 이고 즉시 끊기지 않는다(응원 구단이 달라도, 없어도 열린다 — CHAT-GC-13)")
    void subscribe_opensEventStreamAndStaysOpen() throws Exception {
        String room = room();
        TestUser noTeam = user(null, null);

        try (SseStream stream = openOk(noTeam, room, null)) {
            // 헤더는 첫 쓰기(하트비트 ≤15초)에 도착한다 — 도착 시점은 아래 별도 테스트가 본다
            await().atMost(Duration.ofSeconds(40)).until(() -> stream.status() != -1);
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.contentType()).startsWith("text/event-stream");
            Thread.sleep(1500);
            assertThat(stream.isEnded()).isFalse();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-30] 구독 요청 직후(5초 안에) 200 응답 헤더가 클라이언트에 도착해야 한다 — 연결 성립을 클라이언트가 알 수 있도록")
    void subscribe_responseHeadersReachClientPromptly() {
        String room = room();
        TestUser u = user();

        try (SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", u.token(), null)) {
            awaitRegistered(u, room);
            long start = System.nanoTime();
            await().atMost(Duration.ofSeconds(40)).until(() -> stream.status() != -1);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(stream.status()).isEqualTo(200);
            assertThat(waitedMs).as("서버 등록 뒤 응답 헤더가 클라이언트에 도착하기까지(ms) — 첫 쓰기(하트비트)까지 헤더가 flush 되지 않으면 ~15초")
                    .isLessThan(5_000);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-12] 인증 없는 구독은 스트림을 열기 전에 401 이다")
    void subscribe_withoutToken_is401() {
        String room = room();

        try (SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", null, null)) {
            await().atMost(Duration.ofSeconds(10)).until(() -> stream.status() != -1);
            assertThat(stream.status()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-32] 유휴 스트림에 15초 간격 SSE 주석 :ping 이 오고, 그것은 data 이벤트가 아니다")
    void subscribe_idleStreamReceivesPingComment() {
        String room = room();
        TestUser u = user();

        try (SseStream stream = openOk(u, room, null)) {
            await().atMost(Duration.ofSeconds(25)).until(() ->
                    stream.events().stream().anyMatch(e -> "ping".equals(e.comment())));
            assertThat(stream.dataEvents()).isEmpty();
        }
    }

    // ---------- 실시간 전달 (14, 67, 87) ----------

    @Test
    @DisplayName("[CHAT-GC-87] 다른 사용자의 전송은 구독자에게 event: messages · id: = msgId · 6필드 배열로 오고, 발신자 본인의 스트림에는 오지 않는다(67)")
    void realtime_deliversToOthersButNotToSender() throws Exception {
        String room = room();
        TestUser sender = user();
        TestUser listener = user();

        try (SseStream senderStream = openOk(sender, room, null); SseStream listenerStream = openOk(listener, room, null)) {
            long msgId = msgIdOf(sendOk(sender, room, "실시간 메시지"));

            await().atMost(WAIT).until(() -> !named(listenerStream, "messages").isEmpty());
            SseEvent event = named(listenerStream, "messages").get(0);
            assertThat(event.id()).isEqualTo(String.valueOf(msgId));
            JsonNode item = items(event).get(0);
            assertThat(item.propertyNames()).containsExactlyInAnyOrder(
                    "msgId", "content", "senderNickname", "teamCode", "profileImgUrl", "sentAt");
            assertThat(item.get("msgId").asLong()).isEqualTo(msgId);
            assertThat(item.get("content").asString()).isEqualTo("실시간 메시지");
            assertThat(item.get("senderNickname").asString()).isEqualTo("닉네임" + sender.id());
            assertThat(event.data()).doesNotContain("senderId");
            Thread.sleep(1000);
            assertThat(senderStream.dataEvents()).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-94] 실시간 전달에는 차단 필터가 없다 — 서로 차단한 사용자의 메시지도 스트림에 실린다(숨김은 히스토리 조회에서만)")
    void realtime_hasNoBlockFilter() {
        String room = room();
        TestUser blocker = user();
        TestUser blocked = user();
        given(userBlockRepository.findRelatedAccountIds(blocker.id())).willReturn(Set.of(blocked.id()));

        try (SseStream stream = openOk(blocker, room, null)) {
            long msgId = msgIdOf(sendOk(blocked, room, "차단된 사용자의 메시지"));

            await().atMost(WAIT).until(() -> !named(stream, "messages").isEmpty());
            assertThat(msgIds(named(stream, "messages").get(0))).contains(msgId);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-87] 연속 전송은 150ms(여기선 50ms) 배치로 묶여 오며 모든 msgId 가 오름차순으로 빠짐없이 도착한다")
    void realtime_burstArrivesCompleteAndOrdered() {
        String room = room();
        TestUser listener = user();
        List<Long> sent = new ArrayList<>();

        try (SseStream stream = openOk(listener, room, null)) {
            for (int i = 0; i < 10; i++) {
                TestUser s = user();   // 사용자별 1초 3건 제한을 피한다
                sent.add(msgIdOf(sendOk(s, room, "m" + i)));
            }

            await().atMost(WAIT).until(() -> named(stream, "messages").stream().mapToInt(e -> msgIds(e).size()).sum() >= 10);
            List<Long> received = named(stream, "messages").stream().flatMap(e -> msgIds(e).stream()).toList();
            assertThat(received).isEqualTo(sent);
            assertThat(sent).isSorted();
        }
    }

    // ---------- 신고·blind (77~85, 91, 98) ----------

    @Test
    @DisplayName("[CHAT-GC-77] 신고 → 200, blind 툼스톤이 Kafka 로 가서 구독자 전원(작성자 포함)이 id 없는 deleted 이벤트를 받고 blind 집합에 msgId 가 들어간다. 멱등이며 신고 흔적 저장은 없다")
    void report_blindsMessageAndNotifiesEveryone() {
        String room = room();
        TestUser author = user();
        TestUser reporter = user();
        TestUser bystander = user();

        try (SseStream authorStream = openOk(author, room, null); SseStream reporterStream = openOk(reporter, room, null);
                SseStream bystanderStream = openOk(bystander, room, null)) {
            long msgId = msgIdOf(sendOk(author, room, "문제의 메시지"));
            await().atMost(WAIT).until(() -> Boolean.TRUE.equals(redis.hasKey("chat:game:" + room))
                    && !redis.opsForStream().range("chat:game:" + room, org.springframework.data.domain.Range.unbounded()).isEmpty());

            E2eHttp.Resp first = http.post("/chat/rooms/" + room + "/messages/" + msgId + "/report", reporter.token(), null);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.json().get("success").asBoolean()).isTrue();
            assertThat(first.json().get("data").isNull()).isTrue();
            for (SseStream s : List.of(authorStream, reporterStream, bystanderStream)) {
                await().atMost(WAIT).until(() -> !named(s, "deleted").isEmpty());
                SseEvent deleted = named(s, "deleted").get(0);
                assertThat(deleted.id()).isNull();
                assertThat(deleted.data().replace(" ", "")).isEqualTo("{\"msgId\":" + msgId + "}");
            }
            await().atMost(WAIT).untilAsserted(() ->
                    assertThat(redis.opsForSet().isMember("chat:blind:" + room, String.valueOf(msgId))).isTrue());
            assertThat(redis.getExpire("chat:blind:" + room)).isBetween(1L, clock.secondsUntilNextMidnight() + 1);

            // 멱등: 두 번째 신고도 200, 집합 원소는 1개
            E2eHttp.Resp second = http.post("/chat/rooms/" + room + "/messages/" + msgId + "/report", bystander.token(), null);
            assertThat(second.status()).isEqualTo(200);
            assertThat(redis.opsForSet().size("chat:blind:" + room)).isEqualTo(1L);

            // 83: 신고자·사유·횟수를 어디에도 저장하지 않는다
            assertThat(redis.keys("*report*")).isEmpty();
            verify(userAccountRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @DisplayName("[CHAT-GC-84] blind 된 메시지는 이후 히스토리와 Last-Event-ID 복구 어디에도 나오지 않는다")
    void report_blindedMessageDisappearsFromHistoryAndRecovery() throws Exception {
        String room = room();
        TestUser author = user();
        TestUser reporter = user();
        openRoom(reporter, room);
        long keepId = msgIdOf(sendOk(author, room, "남길 메시지"));
        long msgId = msgIdOf(sendOk(user(), room, "숨길 메시지"));
        await().atMost(WAIT).until(() -> redis.opsForStream().size("chat:game:" + room) != null
                && redis.opsForStream().size("chat:game:" + room) >= 2);
        assertThat(http.post("/chat/rooms/" + room + "/messages/" + msgId + "/report", reporter.token(), null).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> Boolean.TRUE.equals(redis.opsForSet().isMember("chat:blind:" + room, String.valueOf(msgId))));

        E2eHttp.Resp history = http.get("/chat/rooms/" + room + "/messages", reporter.token());
        TestUser late = user();

        assertThat(history.json().get("data").get("messages").size()).isEqualTo(1);
        assertThat(history.json().get("data").get("messages").get(0).get("msgId").asLong()).isEqualTo(keepId);
        try (SseStream recovered = openOk(late, room, String.valueOf(keepId))) {
            Thread.sleep(1500);
            assertThat(named(recovered, "messages")).as("keepId 뒤의 blind 메시지는 복구에 없다").isEmpty();
            assertThat(named(recovered, "reset")).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-80] 본인 메시지 신고는 403, [CHAT-GC-79] 없는 메시지는 404, [CHAT-GC-78] 없는 방은 404, [CHAT-GC-85] msgId 가 정수가 아니면 400 이다")
    void report_failureCases() {
        String room = room();
        TestUser author = user();
        TestUser other = user();
        long msgId = msgIdOf(sendOk(author, room, "내 메시지"));
        await().atMost(WAIT).until(() -> redis.opsForStream().size("chat:game:" + room) != null
                && redis.opsForStream().size("chat:game:" + room) > 0);

        E2eHttp.Resp self = http.post("/chat/rooms/" + room + "/messages/" + msgId + "/report", author.token(), null);
        E2eHttp.Resp missingMessage = http.post("/chat/rooms/" + room + "/messages/9999999/report", other.token(), null);
        E2eHttp.Resp missingRoom = http.post("/chat/rooms/no-such-room/messages/" + msgId + "/report", author.token(), null);
        E2eHttp.Resp notNumber = http.post("/chat/rooms/" + room + "/messages/abc/report", other.token(), null);

        assertThat(self.status()).isEqualTo(403);
        assertThat(self.json().get("message").asString()).isEqualTo("자신의 메시지는 신고할 수 없습니다.");
        assertThat(missingMessage.status()).isEqualTo(404);
        assertThat(missingMessage.json().get("message").asString()).isEqualTo("존재하지 않는 메시지입니다.");
        assertThat(missingRoom.status()).isEqualTo(404);
        assertThat(missingRoom.json().get("message").asString()).isEqualTo("존재하지 않는 채팅방입니다.");
        assertThat(notNumber.status()).isEqualTo(400);
        assertThat(notNumber.json().get("message").asString()).isEqualTo("요청 파라미터 형식이 올바르지 않습니다: msgId");
        assertThat(redis.opsForSet().size("chat:blind:" + room)).isZero();
        assertThat(KafkaReads.count("chat-control", room)).isZero();
    }

    // ---------- 히스토리 (68~75) ----------

    @Test
    @DisplayName("[CHAT-GC-70] 65건 Stream 을 30건씩: 최신순, nextCursor=원본 마지막 msgId, hasNext 는 원본이 30건을 채웠는지. blind·차단 필터가 걸러도 커서는 원본 기준이다")
    void history_cursorPagingWithFilters() {
        String room = room();
        TestUser viewer = user();
        TestUser blockedAuthor = user();
        TestUser author = user();
        openRoom(viewer, room);
        given(userBlockRepository.findRelatedAccountIds(viewer.id())).willReturn(Set.of(blockedAuthor.id()));
        for (long id = 5000; id < 5065; id++) {
            boolean blocked = id >= 5050 && id < 5055;    // 첫 페이지(5064..5035)에 차단 5건
            seedStreamEntry(room, id, blocked ? blockedAuthor.id() : author.id(), "내용" + id);
        }
        redis.opsForSet().add("chat:blind:" + room, "5064", "5063", "5062");   // 첫 페이지에 blind 3건

        JsonNode page1 = http.get("/chat/rooms/" + room + "/messages", viewer.token()).json().get("data");
        long cursor1 = page1.get("nextCursor").asLong();
        JsonNode page2 = http.get("/chat/rooms/" + room + "/messages?cursor=" + cursor1, viewer.token()).json().get("data");
        long cursor2 = page2.get("nextCursor").asLong();
        JsonNode page3 = http.get("/chat/rooms/" + room + "/messages?cursor=" + cursor2, viewer.token()).json().get("data");

        assertThat(page1.propertyNames()).containsExactlyInAnyOrder("messages", "nextCursor", "hasNext");
        assertThat(page1.get("messages")).hasSize(30 - 3 - 5);
        assertThat(page1.get("messages").get(0).get("msgId").asLong()).isEqualTo(5061L);
        assertThat(page1.get("messages").get(0).propertyNames()).containsExactlyInAnyOrder(
                "msgId", "content", "senderNickname", "teamCode", "profileImgUrl", "sentAt");
        assertThat(cursor1).isEqualTo(5035L);
        assertThat(page1.get("hasNext").asBoolean()).isTrue();
        assertThat(page2.get("messages")).hasSize(30);
        assertThat(page2.get("messages").get(0).get("msgId").asLong()).isEqualTo(5034L);
        assertThat(cursor2).isEqualTo(5005L);
        assertThat(page2.get("hasNext").asBoolean()).isTrue();
        assertThat(page3.get("messages")).hasSize(5);
        assertThat(page3.get("nextCursor").asLong()).isEqualTo(5000L);
        assertThat(page3.get("hasNext").asBoolean()).isFalse();
        verify(userBlockRepository, times(3)).findRelatedAccountIds(viewer.id());
    }

    @Test
    @DisplayName("[CHAT-GC-70] 빈 방의 히스토리는 messages [] · nextCursor null · hasNext false 이다. [CHAT-GC-74] cursor=abc 는 400 이다")
    void history_emptyRoomAndBadCursor() {
        String room = room();
        TestUser viewer = user();
        openRoom(viewer, room);

        JsonNode empty = http.get("/chat/rooms/" + room + "/messages", viewer.token()).json().get("data");
        E2eHttp.Resp bad = http.get("/chat/rooms/" + room + "/messages?cursor=abc", viewer.token());

        assertThat(empty.get("messages")).isEmpty();
        assertThat(empty.get("nextCursor").isNull()).isTrue();
        assertThat(empty.get("hasNext").asBoolean()).isFalse();
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("message").asString()).isEqualTo("요청 파라미터 형식이 올바르지 않습니다: cursor");
    }

    // ---------- Last-Event-ID 복구 (37~41) ----------

    private void seedRange(String room, long fromInclusive, long toInclusive, long senderId) {
        for (long id = fromInclusive; id <= toInclusive; id++) {
            seedStreamEntry(room, id, senderId, "복구" + id);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-37] Last-Event-ID 로 재구독하면 그 뒤 엔트리를 페이지(batch-size) 단위 messages 이벤트(id=페이지 마지막 offset)로 오름차순 흘린다. blind·본인 메시지는 제외된다(38)")
    void recovery_pagesExcludeBlindAndOwn() {
        String room = room();
        TestUser author = user();
        TestUser me = user();
        openRoom(me, room);
        for (long id = 100; id <= 109; id++) {
            seedStreamEntry(room, id, id == 103 ? me.id() : author.id(), "복구" + id);
        }
        redis.opsForSet().add("chat:blind:" + room, "105");

        try (SseStream stream = openOk(me, room, "100")) {
            await().atMost(WAIT).until(() -> named(stream, "messages").size() >= 2);
            List<SseEvent> messages = named(stream, "messages");
            assertThat(messages.get(0).id()).as("1페이지 = 101..105, id 는 걸러내기 전 페이지의 마지막 offset").isEqualTo("105");
            assertThat(msgIds(messages.get(0))).containsExactly(101L, 102L, 104L);
            assertThat(messages.get(1).id()).isEqualTo("109");
            assertThat(msgIds(messages.get(1))).containsExactly(106L, 107L, 108L, 109L);
            assertThat(named(stream, "reset")).isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-GC-39] 놓친 건수가 정확히 batch-size×max-batches(5×2=10)이면 reset 이 없고, 1건이라도 더 있으면 messages 2개 뒤 reset 하나다")
    void recovery_resetOnlyWhenMoreThanMaxBatches() throws Exception {
        TestUser author = user();
        String exact = room();
        String over = room();
        TestUser me = user();
        openRoom(me, exact);
        openRoom(me, over);
        seedRange(exact, 100, 110, author.id());   // 마지막 id 100 → 놓친 10건
        seedRange(over, 100, 111, author.id());    // 놓친 11건

        try (SseStream exactStream = openOk(me, exact, "100")) {
            await().atMost(WAIT).until(() -> named(exactStream, "messages").size() >= 2);
            Thread.sleep(1000);
            assertThat(named(exactStream, "messages")).hasSize(2);
            assertThat(named(exactStream, "reset")).as("정확히 상한이면 reset 없음").isEmpty();
        }
        try (SseStream overStream = openOk(me, over, "100")) {
            await().atMost(WAIT).until(() -> !named(overStream, "reset").isEmpty());
            List<String> names = overStream.dataEvents().stream().map(SseEvent::name).toList();
            assertThat(names).containsExactly("messages", "messages", "reset");
            assertThat(named(overStream, "reset").get(0).data().replace(" ", "")).isEqualTo("{}");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-40] Last-Event-ID 가 Stream 의 가장 오래된 엔트리보다 작으면 복구 없이 reset 하나만 온다")
    void recovery_olderThanOldest_sendsResetOnly() {
        String room = room();
        TestUser me = user();
        openRoom(me, room);
        seedRange(room, 100, 104, user().id());

        try (SseStream stream = openOk(me, room, "50")) {
            await().atMost(WAIT).until(() -> !named(stream, "reset").isEmpty());
            assertThat(stream.dataEvents()).extracting(SseEvent::name).containsExactly("reset");
        }
    }

    @Test
    @DisplayName("[CHAT-GC-41] Last-Event-ID 가 정수가 아니면(abc, 음수) 헤더를 무시하고 200 스트림 — 복구도 reset 도 없다")
    void recovery_unparsableHeader_isIgnored() throws Exception {
        String room = room();
        TestUser me = user();
        openRoom(me, room);
        seedRange(room, 100, 104, user().id());

        for (String header : List.of("abc", "-7")) {
            TestUser u = user();
            try (SseStream stream = openOk(u, room, header)) {
                Thread.sleep(1200);
                assertThat(stream.dataEvents()).as("Last-Event-ID=" + header).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구 중 도착한 실시간 메시지는 복구 이벤트 뒤에 나가고 빠지지 않는다(복구 → 실시간 순서)")
    void recovery_thenRealtime_inOrderWithoutLoss() {
        String room = room();
        TestUser author = user();
        TestUser me = user();
        TestUser talker = user();
        openRoom(me, room);
        long base = 90_000_000L;
        seedRange(room, base + 1, base + 4, author.id());

        try (SseStream stream = openOk(me, room, String.valueOf(base + 1))) {   // 가장 오래된 엔트리와 같은 id: reset 아님
            long live = msgIdOf(sendOk(talker, room, "복구 직후 실시간"));

            await().atMost(WAIT).until(() -> named(stream, "messages").stream().anyMatch(e -> msgIds(e).contains(live)));
            List<SseEvent> messages = named(stream, "messages");
            int recoveredIndex = -1;
            int liveIndex = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (msgIds(messages.get(i)).contains(base + 4)) {
                    recoveredIndex = i;
                }
                if (msgIds(messages.get(i)).contains(live)) {
                    liveIndex = i;
                }
            }
            assertThat(recoveredIndex).as("복구 이벤트가 먼저 나가야 한다").isGreaterThanOrEqualTo(0).isLessThan(liveIndex);
            assertThat(named(stream, "reset")).isEmpty();
        }
    }

    // ---------- 축출·퇴장 (34, 35, 45~47, 92) ----------

    @Test
    @DisplayName("[CHAT-GC-34] 같은 사용자가 (다른 방에) 재구독하면 기존 스트림은 서버가 닫는다. 자기 축출 명령이 Kafka 를 돌아와도 새 스트림은 살아 있고 이후 메시지를 받는다(35, 92)")
    void resubscribe_evictsOldStreamAndKeepsNewOne() throws Exception {
        String roomA = room();
        String roomB = room();
        TestUser me = user();
        TestUser talker = user();

        try (SseStream first = openOk(me, roomA, null)) {
            try (SseStream second = openOk(me, roomB, null)) {
                await().atMost(WAIT).until(first::isEnded);
                Thread.sleep(2500); // 자기 축출 명령이 chat-control 을 돌아올 시간
                assertThat(second.isEnded()).as("새 구독은 자기 축출 명령에 끊기지 않는다").isFalse();

                long msgId = msgIdOf(sendOk(talker, roomB, "새 구독으로 오는 메시지"));
                await().atMost(WAIT).until(() -> !named(second, "messages").isEmpty());
                assertThat(msgIds(named(second, "messages").get(0))).contains(msgId);
                for (SseStream s : List.of(first, second)) {
                    assertThat(s.events()).noneMatch(e -> e.data() != null && e.data().contains("subscription-close"));
                }
            }
        }
        List<ConsumerRecord<String, String>> commands = KafkaReads.byKey("chat-control", String.valueOf(me.id()), 2, Duration.ofSeconds(15));
        assertThat(commands).hasSize(2);
        JsonNode evict = E2eHttp.MAPPER.readTree(commands.get(0).value());
        assertThat(evict.propertyNames()).containsExactlyInAnyOrder(
                "type", "targetUserAccountId", "originInstanceId", "allRooms", "gameId");
        assertThat(evict.get("type").asString()).isEqualTo("subscription-close");
        assertThat(evict.get("allRooms").asBoolean()).isTrue();
        assertThat(evict.get("targetUserAccountId").asLong()).isEqualTo(me.id());
        assertThat(evict.get("originInstanceId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장은 200 {success:true,data:null,message:null} 이고 그 스트림이 서버에서 닫힌다. 방 존재를 확인하지 않아 없는 방도 200(46)이며 연속 2회도 200(47)이다")
    void leave_closesStreamAndIsIdempotent() {
        String room = room();
        TestUser me = user();

        try (SseStream stream = openOk(me, room, null)) {
            E2eHttp.Resp first = http.delete("/chat/rooms/" + room + "/subscribe", me.token());

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.json().get("success").asBoolean()).isTrue();
            assertThat(first.json().get("data").isNull()).isTrue();
            assertThat(first.json().get("message").isNull()).isTrue();
            await().atMost(WAIT).until(stream::isEnded);
        }
        assertThat(http.delete("/chat/rooms/" + room + "/subscribe", me.token()).status()).isEqualTo(200);
        assertThat(http.delete("/chat/rooms/어제-방-아무거나/subscribe", me.token()).status()).isEqualTo(200);
        assertThat(http.delete("/chat/rooms/" + room + "/subscribe", null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("[CHAT-GC-45·34] 서버가 SSE 를 닫을 때(퇴장·재구독 축출) ASYNC 재디스패치가 인가에서 거부되지 않는다 — AuthorizationDeniedException·'response is already committed' ERROR 로그가 없다")
    void serverSideClose_doesNotTriggerAuthorizationDeniedOnAsyncDispatch() throws Exception {
        String room = room();
        TestUser me = user();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            // 퇴장(DELETE) 경로
            try (SseStream stream = openOk(me, room, null)) {
                assertThat(http.delete("/chat/rooms/" + room + "/subscribe", me.token()).status()).isEqualTo(200);
                await().atMost(WAIT).until(stream::isEnded);
            }
            // 재구독 축출 경로
            try (SseStream first = openOk(me, room, null)) {
                try (SseStream second = openOk(me, room(), null)) {
                    await().atMost(WAIT).until(first::isEnded);
                }
            }
            // 재디스패치는 complete() 뒤 컨테이너 스레드에서 비동기로 일어난다 — 잠시 지켜본다.
            Thread.sleep(2_000);
        } finally {
            root.detachAppender(appender);
        }
        assertThat(securityRejections(appender)).isEmpty();
    }

    private static List<String> securityRejections(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        List<String> found = new ArrayList<>();
        for (ch.qos.logback.classic.spi.ILoggingEvent event : List.copyOf(appender.list)) {
            String message = event.getFormattedMessage();
            boolean denied = message != null && message.contains("Unable to handle the Spring Security Exception");
            for (ch.qos.logback.classic.spi.IThrowableProxy t = event.getThrowableProxy(); t != null && !denied;
                    t = t.getCause()) {
                denied = t.getClassName().endsWith("AuthorizationDeniedException")
                        || t.getClassName().endsWith("AccessDeniedException");
            }
            if (denied) {
                found.add(event.getLoggerName() + ": " + message);
            }
        }
        return found;
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장은 chat-control 에 allRooms=false + gameId 종료 명령을 발행한다")
    void leave_publishesCloseCommandWithGameId() {
        String room = room();
        TestUser me = user();
        try (SseStream stream = openOk(me, room, null)) {
            http.delete("/chat/rooms/" + room + "/subscribe", me.token());
            await().atMost(WAIT).until(stream::isEnded);
        }

        List<ConsumerRecord<String, String>> commands = KafkaReads.byKey("chat-control", String.valueOf(me.id()), 2, Duration.ofSeconds(15));

        JsonNode leave = E2eHttp.MAPPER.readTree(commands.get(commands.size() - 1).value());
        assertThat(leave.get("allRooms").asBoolean()).isFalse();
        assertThat(leave.get("gameId").asString()).isEqualTo(room);
    }

    @Test
    @DisplayName("[CHAT-GC-44] 열린 스트림은 방 메타가 정리돼도 유지된다 — 정리 뒤 같은 방 재구독은 404")
    void openStreamSurvivesRoomCleanup_resubscribeIs404() throws Exception {
        String room = room();
        TestUser me = user();
        TestUser other = user();

        try (SseStream stream = openOk(me, room, null)) {
            redis.unlink("chat:room:" + room);                                  // 자정 정리
            Game yesterday = com.skhynix.chat.support.ChatFixtures.game(room, clock.today().minusDays(1).atTime(18, 30), "FINISHED");
            given(gameRepository.findByNaverGameId(room)).willReturn(Optional.of(yesterday));
            Thread.sleep(1500);

            assertThat(stream.isEnded()).isFalse();
            // Accept 에 JSON 을 함께 실어 [CHAT-GC-31] 의 Accept 문제와 분리해서 본다
            try (SseStream again = http.subscribe("/chat/rooms/" + room + "/subscribe", other.token(), null,
                    "application/json, text/event-stream")) {
                await().atMost(Duration.ofSeconds(10)).until(() -> again.status() != -1);
                assertThat(again.status()).isEqualTo(404);
            }
        }
    }
}
