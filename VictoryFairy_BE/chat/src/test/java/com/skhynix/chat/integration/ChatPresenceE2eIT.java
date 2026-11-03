package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import com.skhynix.chat.integration.E2eHttp.SseEvent;
import com.skhynix.chat.integration.E2eHttp.SseStream;
import com.skhynix.chat.room.service.RoomLifecycleJob;
import com.skhynix.chat.shared.ChatClock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import tools.jackson.databind.JsonNode;

/**
 * 접속 인원(presence)을 실제 HTTP/SSE 로 확인한다(앱 1개 + 실 Redis + 실 Kafka). 파드가 둘 이상인 시나리오는
 * {@link ChatPresencePodsIT}, Redis 정지는 {@link ChatPresenceRedisOutageIT} 가 맡는다.
 *
 * <p>주기 집계는 기본 2초라 "2초 안에"는 5초 여유로 본다. 생존 갱신(15초)은 하트비트 스케줄러가 앱 기동 시각 기준으로 돌려
 * 최악 15초 뒤이므로 그것을 기다리는 테스트는 20초까지 본다.
 */
class ChatPresenceE2eIT extends E2eSupport {

    private static final Duration WITHIN_BROADCAST = Duration.ofSeconds(5);
    private static final Duration WITHIN_HEARTBEAT = Duration.ofSeconds(21);

    @Autowired
    private RoomLifecycleJob roomLifecycleJob;

    // ---------- 도구 ----------

    private SseStream open(TestUser user, String room) {
        return open(user, room, null);
    }

    private SseStream open(TestUser user, String room, String lastEventId) {
        SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", user.token(), lastEventId);
        awaitRegistered(user, room);
        return stream;
    }

    private static List<Long> counts(SseStream stream) {
        return stream.presenceEvents().stream().map(e -> E2eHttp.MAPPER.readTree(e.data()).get("count").asLong()).toList();
    }

    private static long lastCount(SseStream stream) {
        List<Long> counts = counts(stream);
        return counts.isEmpty() ? -1 : counts.get(counts.size() - 1);
    }

    private void awaitCount(SseStream stream, long expected) {
        await().atMost(WITHIN_BROADCAST).untilAsserted(() ->
                assertThat(lastCount(stream)).as("받은 presence 값들: " + counts(stream)).isEqualTo(expected));
    }

    private static String zkey(String room) {
        return "chat:presence:" + room;
    }

    private static String hkey(String room) {
        return "chat:presence-owner:" + room;
    }

    private Double score(String room, long user) {
        return redis.opsForZSet().score(zkey(room), String.valueOf(user));
    }

    private Object owner(String room, long user) {
        return redis.opsForHash().get(hkey(room), String.valueOf(user));
    }

    private long redisNowMs() {
        Long now = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time(TimeUnit.MILLISECONDS));
        return now == null ? -1 : now;
    }

    // ---------- 인원의 정의 ----------

    @Test
    @DisplayName("[CHAT-PR-3] 방에 혼자 구독하면 :connected 다음에 {\"count\":1} 을 받는다 — 본인이 포함된다")
    void aloneInRoomSeesOneIncludingSelf() {
        String room = room();
        TestUser a = user();

        try (SseStream stream = open(a, room)) {
            await().atMost(WITHIN_BROADCAST).until(() -> !stream.presenceEvents().isEmpty());
            assertThat(stream.events().get(0).comment()).as("첫 프레임").isEqualTo("connected");
            assertThat(stream.presenceEvents().get(0).data().replace(" ", "")).isEqualTo("{\"count\":1}");
        }
    }

    @Test
    @DisplayName("[CHAT-PR-11] 다른 구독자 2명이 있는 방에 구독하면 :connected 뒤 1초 안에 {\"count\":3} 1건을 받는다")
    void joinerGetsCurrentCountRightAfterConnected() {
        String room = room();
        TestUser b = user();
        TestUser c = user();
        TestUser joiner = user();

        try (SseStream sb = open(b, room); SseStream sc = open(c, room)) {
            awaitCount(sb, 2);
            try (SseStream stream = open(joiner, room)) {
                await().atMost(Duration.ofSeconds(1)).until(() -> !stream.presenceEvents().isEmpty());
                assertThat(stream.events().get(0).comment()).isEqualTo("connected");
                assertThat(counts(stream)).containsExactly(3L);
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-3] 같은 방 구독자는 모두 같은 값을 받는다")
    void everySubscriberConvergesOnSameValue() {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room); SseStream sc = open(c, room)) {
            awaitCount(sa, 3);
            awaitCount(sb, 3);
            awaitCount(sc, 3);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-4] 차단 관계(A 가 B 를 차단)와 응원 구단 없음은 인원에 영향이 없다 — A·B·구단 없는 C 모두 3을 받는다")
    void blockingAndNoTeamDoNotAffectCount() {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser noTeam = user(null, "p/n.jpg");
        given(userBlockRepository.findRelatedAccountIds(a.id())).willReturn(Set.of(b.id()));
        given(userBlockRepository.findRelatedAccountIds(b.id())).willReturn(Set.of(a.id()));

        try (SseStream sa = open(a, room); SseStream sb = open(b, room); SseStream sn = open(noTeam, room)) {
            awaitCount(sa, 3);
            awaitCount(sb, 3);
            awaitCount(sn, 3);
        }
    }

    // ---------- 반영 ----------

    @Test
    @DisplayName("[CHAT-PR-5][CHAT-PR-12] A 구독 중 B 가 구독하면 A 는 값 2를 받고, B 의 소유자 기록은 이 파드의 instanceId 다")
    void joinIsReflectedAndOwnerRecorded() {
        String room = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room)) {
            awaitCount(sa, 1);
            try (SseStream sb = open(b, room)) {
                awaitCount(sa, 2);
                assertThat(owner(room, b.id())).isEqualTo(registry.instanceId());
                assertThat(owner(room, a.id())).isEqualTo(registry.instanceId());
                assertThat(score(room, b.id())).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-8][CHAT-PR-12] B 가 DELETE /subscribe 로 퇴장하면 A 는 45초가 아니라 2초대에 값 1을 받고, B 의 ZSCORE·HGET 은 둘 다 nil 이다")
    void explicitLeaveRemovesAtOnceAndNotifiesOthers() {
        String room = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);

            assertThat(http.delete("/chat/rooms/" + room + "/subscribe", b.token()).status()).isEqualTo(200);

            awaitCount(sa, 1);
            await().atMost(WITHIN_BROADCAST).untilAsserted(() -> {
                assertThat(score(room, b.id())).isNull();
                assertThat(owner(room, b.id())).isNull();
            });
            assertThat(score(room, a.id())).isNotNull();
        }
    }

    @Test
    @DisplayName("[CHAT-PR-8] B 가 다른 방을 구독하면 원래 방의 A 는 값 1을 받고 B 는 원래 방에서 지워진다. 새 방에는 남는다")
    void movingToAnotherRoomRemovesFromOldRoom() {
        String room1 = room();
        String room2 = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room1); SseStream sb1 = open(b, room1)) {
            awaitCount(sa, 2);

            try (SseStream sb2 = open(b, room2)) {
                awaitCount(sa, 1);
                await().atMost(WITHIN_BROADCAST).untilAsserted(() -> {
                    assertThat(score(room1, b.id())).isNull();
                    assertThat(owner(room1, b.id())).isNull();
                });
                assertThat(score(room2, b.id())).as("새 방에는 남는다").isNotNull();
                awaitCount(sb2, 1);
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-9] 같은 파드에서 같은 방을 재구독하면 옛 구독이 종료돼도 다른 구독자의 값이 바뀌지 않고, 1 적은 값의 프레임도 오지 않으며, 그 계정은 남는다")
    void resubscribeOnSamePodKeepsCount() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room); SseStream first = open(b, room)) {
            awaitCount(sa, 2);

            try (SseStream second = open(b, room)) {
                await().atMost(WITHIN_BROADCAST).until(first::isEnded);   // 옛 구독은 last-one-wins 로 닫혔다
                Thread.sleep(5_000);   // 집계 주기 두 번 이상

                assertThat(counts(sa)).as("A 가 받은 값: 1 적은 값이 없다").containsExactly(1L, 2L);
                assertThat(score(room, b.id())).isNotNull();
                assertThat(owner(room, b.id())).isEqualTo(registry.instanceId());
                assertThat(lastCount(second)).isEqualTo(2L);
            }
        }
    }

    // ---------- 전달 규칙 ----------

    @Test
    @DisplayName("[CHAT-PR-14] 변화 없는 방에서는 집계 주기가 여러 번 지나도 presence 가 추가로 오지 않는다(:ping 만 온다)")
    void noFrameWhileCountIsStable() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);
            List<Long> before = counts(sa);

            Thread.sleep(7_000);

            assertThat(counts(sa)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-15][CHAT-PR-16] presence 의 data 키는 count 하나뿐이고 계정 식별 정보가 없으며 SSE id 필드가 없다")
    void presenceDataShapeAndNoId() {
        String room = room();
        TestUser a = user();
        TestUser b = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);

            for (SseEvent event : sa.presenceEvents()) {
                JsonNode data = E2eHttp.MAPPER.readTree(event.data());
                assertThat(data.propertyNames()).containsExactly("count");
                assertThat(data.get("count").isIntegralNumber()).isTrue();
                assertThat(data.get("count").asLong()).isGreaterThanOrEqualTo(0);
                assertThat(event.id()).as("id: 없음").isNull();
                assertThat(event.data()).doesNotContain(String.valueOf(a.id())).doesNotContain(a.uid()).doesNotContain("닉네임")
                        .doesNotContain("OB");
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-16] 재접속하면 Last-Event-ID 는 마지막 messages 의 id 그대로이고, 새 스트림의 첫 값은 현재 인원 1건이다(옛 presence 는 재생되지 않는다)")
    void reconnectGetsOneFreshPresenceAndLastEventIdIsMessageId() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser talker = user();
        openRoom(a, room);

        String lastEventId;
        try (SseStream first = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(first, 2);
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                sendOk(talker, room, "복구 기준 메시지");
                return !first.dataEvents().stream().filter(e -> "messages".equals(e.name())).toList().isEmpty();
            });
            SseEvent lastMessage = first.dataEvents().stream().filter(e -> "messages".equals(e.name())).reduce((x, y) -> y)
                    .orElseThrow();
            lastEventId = lastMessage.id();
            assertThat(lastEventId).as("Last-Event-ID 는 messages 의 id").matches("\\d+");
            assertThat(first.presenceEvents()).allMatch(e -> e.id() == null);
        }

        try (SseStream second = open(a, room, lastEventId); SseStream sb = open(b, room)) {
            await().atMost(WITHIN_BROADCAST).until(() -> !second.presenceEvents().isEmpty());
            Thread.sleep(1_000);
            assertThat(second.presenceEvents()).as("재접속 뒤 presence 는 현재 인원 1건뿐 — 첫 스트림이 받은 값들이 재생되지 않는다")
                    .hasSize(1);
            assertThat(second.presenceEvents().get(0).id()).isNull();
        }
    }

    @Test
    @DisplayName("[CHAT-PR-19] presence 가 끼어도 :connected 가 첫 프레임이고, messages 는 id: = msgId 로 오고, :ping 이 계속 온다. presence 에는 id 가 없다")
    void existingEventsKeepFormat() {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser talker = user();
        openRoom(a, room);

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);
            long[] msgId = new long[1];
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                msgId[0] = msgIdOf(sendOk(talker, room, "형식 확인"));
                return sa.dataEvents().stream().anyMatch(e -> "messages".equals(e.name()));
            });
            await().atMost(WITHIN_HEARTBEAT).until(() -> sa.events().stream().anyMatch(e -> "ping".equals(e.comment())));

            List<SseEvent> events = sa.events();
            assertThat(events.get(0).comment()).isEqualTo("connected");
            SseEvent message = sa.dataEvents().stream().filter(e -> "messages".equals(e.name())).findFirst().orElseThrow();
            assertThat(message.id()).matches("\\d+");
            assertThat(message.data()).contains("형식 확인");
            assertThat(sa.dataEvents()).extracting(SseEvent::name).containsOnly("messages");
            assertThat(sa.presenceEvents()).allMatch(e -> e.id() == null);
        }
    }

    // ---------- 키 수명 · 생존 갱신 ----------

    @Test
    @DisplayName("[CHAT-PR-20] 구독 중인 방의 두 키 TTL 은 0 보다 크고 90 이하다")
    void keysHaveBoundedTtlWhileSubscribed() {
        String room = room();
        TestUser a = user();

        try (SseStream sa = open(a, room)) {
            awaitCount(sa, 1);

            assertThat(redis.getExpire(zkey(room))).isBetween(1L, 90L);
            assertThat(redis.getExpire(hkey(room))).isBetween(1L, 90L);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-6] 메시지 없이 유휴인 구독의 score 는 하트비트 주기(15초)마다 갱신되고, 소유자가 이미 다른 파드로 기록돼 있으면 바뀌지 않으며, 소유 기록이 없을 때만 자기로 채워진다")
    void heartbeatRefreshesScoreWithoutStealingOwner() {
        String room = room();
        TestUser a = user();   // 소유자가 다른 파드로 기록된 계정
        TestUser b = user();   // 소유 기록이 사라진 계정

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);
            double scoreA = score(room, a.id());
            redis.opsForHash().put(hkey(room), String.valueOf(a.id()), "other-pod");
            redis.opsForHash().delete(hkey(room), String.valueOf(b.id()));

            await().atMost(WITHIN_HEARTBEAT).until(() -> score(room, a.id()) > scoreA);

            assertThat(owner(room, a.id())).as("이미 있는 소유자를 빼앗지 않는다").isEqualTo("other-pod");
            await().atMost(WITHIN_BROADCAST).untilAsserted(() ->
                    assertThat(owner(room, b.id())).as("없을 때만 자기로 기록").isEqualTo(registry.instanceId()));
            assertThat(lastCount(sa)).as("유휴 동안 인원이 줄지 않는다").isEqualTo(2L);
        }
    }

    // ---------- 만료 ----------

    @Test
    @DisplayName("[CHAT-PR-7] 크래시한 파드의 계정(서버 시각 -46초)은 다음 집계에서 구독자 값에서 빠지고 소유 HASH 에서도 사라진다. 살아 있는 구독자는 영향이 없다")
    void deadPodMembersExpireFromCountAndOwnerHash() {
        String room = room();
        TestUser a = user();

        try (SseStream sa = open(a, room)) {
            awaitCount(sa, 1);
            redis.opsForZSet().add(zkey(room), "990001", redisNowMs());
            redis.opsForHash().put(hkey(room), "990001", "dead-pod");
            awaitCount(sa, 2);

            redis.opsForZSet().add(zkey(room), "990001", redisNowMs() - 46_000);

            awaitCount(sa, 1);
            assertThat(score(room, 990001L)).isNull();
            assertThat(owner(room, 990001L)).isNull();
            assertThat(owner(room, a.id())).isEqualTo(registry.instanceId());
        }
    }

    @Test
    @DisplayName("[CHAT-PR-30] 서버 시각 -40초 member 는 45초 만료 창 안이라 이 파드의 집계에서 빠지지 않는다(파드 시각이 아니라 Redis 시각으로 판정)")
    void memberInsideWindowByRedisClockIsKept() throws Exception {
        String room = room();
        TestUser a = user();

        try (SseStream sa = open(a, room)) {
            awaitCount(sa, 1);
            redis.opsForZSet().add(zkey(room), "990002", redisNowMs() - 40_000);
            redis.opsForHash().put(hkey(room), "990002", "other-pod");

            awaitCount(sa, 2);
            Thread.sleep(2_500);

            assertThat(lastCount(sa)).isEqualTo(2L);
            assertThat(score(room, 990002L)).isNotNull();
        }
    }

    // ---------- 키 소실 복구 ----------

    @Test
    @DisplayName("[CHAT-PR-10][CHAT-PR-31] 구독 3명인 방에서 두 키를 지워도 15초 안에 두 키가 3명과 소유 파드로 다시 채워지고, 그동안 구독자 3명 중 누구도 새 프레임(0·1·2 등 3 미만 값)을 받지 않는다")
    void repopulatesAfterKeysAreLost() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room); SseStream sc = open(c, room)) {
            await().atMost(WITHIN_BROADCAST).untilAsserted(() -> {
                assertThat(lastCount(sa)).isEqualTo(3L);
                assertThat(lastCount(sb)).isEqualTo(3L);
                assertThat(lastCount(sc)).isEqualTo(3L);
            });
            Thread.sleep(2_500);   // 입장 직후의 값들이 모두 정착하도록
            List<Long> beforeA = counts(sa);
            List<Long> beforeB = counts(sb);
            List<Long> beforeC = counts(sc);

            redis.delete(List.of(zkey(room), hkey(room)));

            await().atMost(Duration.ofSeconds(19)).untilAsserted(() -> {
                for (TestUser u : List.of(a, b, c)) {
                    assertThat(score(room, u.id())).as("score " + u.id()).isNotNull();
                    assertThat(owner(room, u.id())).as("owner " + u.id()).isEqualTo(registry.instanceId());
                }
            });
            Thread.sleep(2_500);   // 채워진 뒤 집계 한 주기 이상

            assertThat(counts(sa)).as("A: 3 -> 0 -> 3 같은 프레임이 없다").isEqualTo(beforeA);
            assertThat(counts(sb)).isEqualTo(beforeB);
            assertThat(counts(sc)).isEqualTo(beforeC);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-31][CHAT-PR-32] 키 소실 중 새로 구독한 사람은 4 미만 초기 값을 받지 않고, 키가 다시 채워진 뒤 첫 주기에 count 4 를 1건 받으며, 기존 구독자는 3 미만 값 없이 4 를 받는다")
    void subscriberDuringKeyLossGetsFirstValueOnlyAfterRepopulation() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        TestUser c = user();
        TestUser d = user();

        try (SseStream sa = open(a, room); SseStream sb = open(b, room); SseStream sc = open(c, room)) {
            await().atMost(WITHIN_BROADCAST).untilAsserted(() -> assertThat(lastCount(sa)).isEqualTo(3L));
            Thread.sleep(2_500);
            int framesBeforeLoss = counts(sa).size();
            redis.delete(List.of(zkey(room), hkey(room)));

            try (SseStream sd = open(d, room)) {
                await().atMost(Duration.ofSeconds(19)).untilAsserted(() ->
                        assertThat(counts(sd)).as("D 가 받은 값").isEqualTo(List.of(4L)));
                Thread.sleep(2_500);
                assertThat(counts(sd)).as("4 미만 값이 없고 1건뿐").containsExactly(4L);
                assertThat(counts(sa).subList(framesBeforeLoss, counts(sa).size())).as("키 소실 뒤 기존 구독자가 받은 값").allMatch(v -> v >= 3L);
                assertThat(lastCount(sa)).isEqualTo(4L);
            }
        }
    }

    // ---------- 자정 정리 ----------

    @Test
    @DisplayName("[CHAT-PR-21][CHAT-PR-22] 자정 정리가 어제 방 키를 지워도 presence 두 키는 UNLINK 대상이 아니고 KEYS·SCAN 도 없으며, 열린 스트림은 0이나 1 적은 값 없이 계속 이어지고 퇴장하면 남은 사람이 1을 받는다")
    void midnightCleanupDoesNotDisturbOpenStreams() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        ChatClock chatClock = new ChatClock();
        openRoom(a, room);

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            awaitCount(sa, 2);
            redis.opsForSet().add("chat:rooms:" + ChatClock.roomDate(chatClock.today().minusDays(1)), room);

            List<RedisMonitor.Entry> commands;
            try (RedisMonitor monitor = new RedisMonitor()) {
                int mark = monitor.mark();
                roomLifecycleJob.run();
                Thread.sleep(300);
                commands = monitor.since(mark);
            }
            assertThat(redis.hasKey("chat:room:" + room)).as("정리가 실제로 어제 방 meta 를 지웠다").isFalse();
            assertThat(commands).extracting(RedisMonitor.Entry::command).doesNotContain("keys", "scan");
            assertThat(commands).filteredOn(e -> e.command().equals("unlink") || e.command().equals("del"))
                    .noneMatch(e -> e.touches("chat:presence"));

            Thread.sleep(5_000);   // 집계 두 주기 이상
            assertThat(counts(sa)).as("A 가 받은 값").containsExactly(1L, 2L);
            assertThat(counts(sb)).as("B 가 받은 값").containsExactly(2L);
            assertThat(redis.hasKey(zkey(room))).isTrue();
            assertThat(redis.hasKey(hkey(room))).isTrue();

            assertThat(http.delete("/chat/rooms/" + room + "/subscribe", b.token()).status()).isEqualTo(200);
            awaitCount(sa, 1);
        }
    }

    // ---------- 만료 창 ·  Redis 오류(키 타입 충돌로 presence 명령만 실패시킨다) ----------

    @Test
    @DisplayName("[CHAT-PR-25][CHAT-PR-23][CHAT-PR-24] 방 확인은 통과하고 presence 명령만 실패하면 구독은 200 으로 열리고 초기 presence 는 생략되며, 스트림이 유지돼 messages 가 계속 오고 어떤 presence 프레임(null count 포함)도 오지 않는다")
    void presenceCommandFailureKeepsStreamAndOmitsFrames() throws Exception {
        String room = room();
        TestUser a = user();
        TestUser talker = user();
        openRoom(a, room);
        redis.opsForValue().set(zkey(room), "wrongtype");   // ZADD/ZRANGEBYSCORE 가 WRONGTYPE 으로 실패한다

        try (SseStream sa = open(a, room)) {
            await().atMost(WITHIN_BROADCAST).until(() -> sa.status() == 200 && !sa.events().isEmpty());
            assertThat(sa.events().get(0).comment()).isEqualTo("connected");

            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                sendOk(talker, room, "presence 장애 중에도 전달");
                return sa.dataEvents().stream().anyMatch(e -> "messages".equals(e.name()));
            });
            Thread.sleep(5_000);   // 집계 두 주기 이상 실패

            assertThat(sa.presenceEvents()).as("presence 프레임 없음").isEmpty();
            assertThat(sa.isEnded()).as("스트림은 닫히지 않는다").isFalse();
        } finally {
            redis.delete(zkey(room));
        }
    }

    @Test
    @DisplayName("[CHAT-PR-26][CHAT-PR-32][CHAT-PR-10] 충돌이 사라지면 파드 재시작 없이 다음 생존 갱신과 집계 안에 실제 인원이 나간다")
    void recoversWithoutRestartAfterFailureClears() {
        String room = room();
        TestUser a = user();
        TestUser b = user();
        openRoom(a, room);
        redis.opsForValue().set(zkey(room), "wrongtype");

        try (SseStream sa = open(a, room); SseStream sb = open(b, room)) {
            await().atMost(WITHIN_BROADCAST).until(() -> sa.status() == 200 && sb.status() == 200);
            assertThat(sa.presenceEvents()).isEmpty();

            redis.delete(zkey(room));

            await().atMost(WITHIN_HEARTBEAT).untilAsserted(() -> {
                assertThat(lastCount(sa)).as("A 가 받은 값: " + counts(sa)).isEqualTo(2L);
                assertThat(lastCount(sb)).isEqualTo(2L);
            });
        }
    }
}
