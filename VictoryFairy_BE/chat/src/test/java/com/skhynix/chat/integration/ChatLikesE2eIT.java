package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.websupport.jwt.JwtProperties;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 경기 실시간 좋아요 E2E(요구사항 game-chat-likes.md). 실 Redis·Kafka(Testcontainers) 위에 chat 앱 전체(보안 필터 포함)를 띄워
 * HTTP 로 두드린다. DB 는 목이다.
 *
 * <p>"어떤 Redis 명령이 나갔는가"는 {@code INFO commandstats} 호출 수 차이로, "실제로 무엇이 발행됐는가"는 {@code chat:likes}
 * 를 직접 구독하는 프로브({@link LikesProbe})로 본다. 메시지를 보내는 테스트(history-writer 의 비동기 XADD 가 남는다)는
 * 명령 수를 재는 테스트 뒤로 순서를 미뤘다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatLikesE2eIT extends E2eSupport {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final HttpClient RAW = HttpClient.newHttpClient();

    /** 좋아요 경로가 일으켜도 되는 Redis 명령(프로브 자신의 조회·연결 핸드셰이크 포함). 이 밖의 명령이 늘면 위반이다. */
    private static final Set<String> ALLOWED_COMMANDS = Set.of("publish", "info", "dbsize", "ping", "hello", "auth",
            "select", "subscribe", "unsubscribe", "client|setinfo", "client|setname", "client|id", "pubsub|numsub",
            "command|docs", "config|get");

    private static LikesProbe probe;

    @Autowired
    private MeterRegistry meters;

    private static synchronized LikesProbe probe() {
        if (probe == null) {
            probe = new LikesProbe();
        }
        return probe;
    }

    @AfterAll
    static void closeProbe() {
        if (probe != null) {
            probe.close();
        }
    }

    // ---------- 도구 ----------

    private E2eHttp.Resp like(TestUser user, String gameId) {
        return http.post("/chat/rooms/" + gameId + "/likes", user.token(), null);
    }

    private E2eHttp.Resp rawPost(String path, String token, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        HttpResponse<String> response = RAW.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new E2eHttp.Resp(response.statusCode(), response.body());
    }

    private double dropped(String reason) {
        return meters.get("chat.likes.dropped").tag("reason", reason).counter().count();
    }

    private double publishFailed() {
        return meters.get("chat.likes.publish.failed").counter().count();
    }

    private double allDropped() {
        return dropped("rate-limit") + dropped("no-team") + dropped("team-lookup-failed") + dropped("queue-full");
    }

    /** gameId 로 발행된 chat:likes 메시지 원문. */
    private List<String> publishedFor(String gameId) {
        return probe().published().stream().filter(m -> m.contains("\"" + gameId + "\"")).toList();
    }

    private void awaitLikesSubscribed() {
        await().atMost(WAIT).until(() -> probe().gatewaySubscribers() >= 1);
    }

    private E2eHttp.SseStream subscribe(TestUser user, String room) {
        return subscribe(user, room, null);
    }

    private E2eHttp.SseStream subscribe(TestUser user, String room, String lastEventId) {
        E2eHttp.SseStream stream = http.subscribe("/chat/rooms/" + room + "/subscribe", user.token(), lastEventId);
        awaitRegistered(user, room);
        awaitLikesSubscribed();
        return stream;
    }

    private static List<E2eHttp.SseEvent> likesOf(E2eHttp.SseStream stream) {
        return stream.dataEvents().stream().filter(e -> "likes".equals(e.name())).toList();
    }

    private void restubTeam(TestUser user, String teamCode) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(teamCode);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(user.id())).willReturn(Optional.of(support));
    }

    private static Map<TopicPartition, Long> kafkaEnds() {
        Properties p = new Properties();
        p.put("bootstrap.servers", Containers.kafkaBootstrapServers());
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (String topic : List.of(Containers.MESSAGES_TOPIC, Containers.CONTROL_TOPIC)) {
                consumer.partitionsFor(topic).forEach(i -> partitions.add(new TopicPartition(i.topic(), i.partition())));
            }
            return new HashMap<>(consumer.endOffsets(partitions));
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 전송 · 전달 해피 패스 ----------

    @Test
    @Order(1)
    @DisplayName("[CHAT-LK-1][CHAT-LK-9][CHAT-LK-21][CHAT-LK-24][CHAT-LK-28][CHAT-LK-42] KIA 응원 계정이 LG-두산 경기에 좋아요 → 202 본문 없음, 발행 원문은 {gameId,teamCode} 두 필드 JSON, 그 방 구독자는 event: likes / data: [\"HT\"] 를 id 없이 받는다")
    void like_202EmptyBody_publishedExactJson_subscriberGetsLikesWithoutId() {
        String room = room();
        TestUser subscriber = user("OB", "p/s.jpg");
        TestUser liker = user("HT", "p/l.jpg");
        try (E2eHttp.SseStream stream = subscribe(subscriber, room)) {
            E2eHttp.Resp resp = like(liker, room);

            assertThat(resp.status()).isEqualTo(202);
            assertThat(resp.body()).as("ApiResponse 래퍼가 아니라 본문 길이 0").isEmpty();
            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
            E2eHttp.SseEvent event = likesOf(stream).get(0);
            assertThat(event.name()).isEqualTo("likes");
            assertThat(event.id()).as("likes 는 SSE id 가 없다").isNull();
            assertThat(event.data()).isEqualTo("[\"HT\"]");
            assertThat(publishedFor(room)).containsExactly("{\"gameId\":\"" + room + "\",\"teamCode\":\"HT\"}");
        }
    }

    @Test
    @Order(2)
    @DisplayName("[CHAT-LK-30] 발신자도 자기 좋아요를 받는다 — 방에 본인 한 명만 구독 중이어도 likes [\"OB\"] 를 받는다")
    void senderReceivesOwnLike() {
        String room = room();
        TestUser me = user("OB", "p/me.jpg");
        try (E2eHttp.SseStream stream = subscribe(me, room)) {
            assertThat(like(me, room).status()).isEqualTo(202);

            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
            assertThat(likesOf(stream).get(0).data()).isEqualTo("[\"OB\"]");
        }
    }

    @Test
    @Order(3)
    @DisplayName("[CHAT-LK-31] 차단 관계가 있어도 likes 는 가리지 않는다 — A 가 B 를 차단해도 B 의 구단 코드가 A 의 likes 에 실린다")
    void blockedUserCodeStillDelivered() {
        String room = room();
        TestUser a = user("OB", "p/a.jpg");
        TestUser b = user("LG", "p/b.jpg");
        given(userBlockRepository.findRelatedAccountIds(a.id())).willReturn(Set.of(b.id()));
        try (E2eHttp.SseStream stream = subscribe(a, room)) {
            assertThat(like(b, room).status()).isEqualTo(202);

            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
            assertThat(likesOf(stream).get(0).data()).isEqualTo("[\"LG\"]");
        }
    }

    @Test
    @Order(4)
    @DisplayName("[CHAT-LK-42][CHAT-LK-47] 조용한 방의 좋아요 1회는 프레임 정확히 1개이고, 그 뒤 창이 끝나도 빈 배열 프레임이 오지 않는다")
    void singleLike_exactlyOneFrame_noEmptyArrayAfterwards() {
        String room = room();
        TestUser s = user("OB", "p/s.jpg");
        TestUser l = user("HT", "p/l.jpg");
        try (E2eHttp.SseStream stream = subscribe(s, room)) {
            like(l, room);
            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);

            sleep(700);

            assertThat(likesOf(stream)).hasSize(1);
            assertThat(stream.dataEvents()).noneMatch(e -> "[]".equals(e.data()));
        }
    }

    @Test
    @Order(5)
    @DisplayName("[CHAT-LK-46][CHAT-LK-27] 4명이 3초간 연타하면 구독자당 likes 프레임은 이론 상한(1 + 경과/창)을 넘지 않고, 프레임마다 중복 없는 구단 코드 배열이다")
    void sustainedLikes_frameCountBoundedByWindow() throws Exception {
        String room = room();
        TestUser s = user("OB", "p/s.jpg");
        List<TestUser> likers = List.of(user("HT", "p/1"), user("LG", "p/2"), user("OB", "p/3"), user("SK", "p/4"));
        try (E2eHttp.SseStream stream = subscribe(s, room)) {
            ExecutorService pool = Executors.newFixedThreadPool(likers.size());
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (TestUser liker : likers) {
                futures.add(pool.submit(() -> {
                    while (System.nanoTime() - start < 3_000_000_000L) {
                        assertThat(like(liker, room).status()).isEqualTo(202);
                        sleep(110);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            sleep(400);

            List<E2eHttp.SseEvent> frames = likesOf(stream);
            assertThat(frames.size()).as("프레임 수").isLessThanOrEqualTo((int) (1 + (elapsedMs + 400) / 100));
            assertThat(frames.size()).as("연타 중에는 창 단위로 계속 나간다").isGreaterThanOrEqualTo(15);
            for (E2eHttp.SseEvent frame : frames) {
                assertThat(frame.id()).isNull();
                List<String> codes = List.of(E2eHttp.MAPPER.readValue(frame.data(), String[].class));
                assertThat(codes).doesNotHaveDuplicates().isSubsetOf("HT", "LG", "OB", "SK").isNotEmpty();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("[CHAT-LK-32] 구독자가 없을 때의 좋아요는 사라지고(창도 안 열림), 그 뒤 구독한 사용자는 그것을 받지 않으며 다음 좋아요는 바로 받는다")
    void likeWithoutSubscribers_notReplayedToLateSubscriber_nextOneImmediate() {
        String room = room();
        TestUser l = user("HT", "p/l.jpg");
        TestUser late = user("OB", "p/late.jpg");
        assertThat(like(l, room).status()).isEqualTo(202);
        await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);
        sleep(300);

        try (E2eHttp.SseStream stream = subscribe(late, room)) {
            sleep(700);
            assertThat(likesOf(stream)).as("구독 전의 좋아요는 받지 않는다").isEmpty();

            like(l, room);
            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
            assertThat(likesOf(stream).get(0).data()).isEqualTo("[\"HT\"]");
        }
    }

    @Test
    @Order(7)
    @DisplayName("[CHAT-LK-29] 끊긴 동안의 좋아요는 Last-Event-ID 재구독에서 재생되지 않고, 재구독 이후의 좋아요부터 받는다")
    void likesWhileDisconnected_notReplayedOnResubscribe() throws Exception {
        String room = room();
        TestUser s = user("OB", "p/s.jpg");
        TestUser l = user("HT", "p/l.jpg");
        openRoom(s, room);
        long msgId = msgIdOf(sendOk(l, room, "복구 기준 메시지"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                redis.opsForStream().range("chat:game:" + room, org.springframework.data.domain.Range.unbounded()))
                .extracting(r -> r.getId().getValue()).contains(msgId + "-1"));

        try (E2eHttp.SseStream first = subscribe(s, room)) {
            assertThat(first).isNotNull();
            assertThat(http.delete("/chat/rooms/" + room + "/subscribe", s.token()).status()).isEqualTo(200);
        }
        await().atMost(WAIT).until(() -> registry.count(room) == 0);
        TestUser l2 = user("HT", "p/l2.jpg");
        for (int i = 0; i < 3; i++) {
            like(l2, room);
        }
        await().atMost(WAIT).until(() -> publishedFor(room).size() >= 3);
        sleep(300);

        try (E2eHttp.SseStream second = subscribe(s, room, String.valueOf(msgId))) {
            sleep(900);
            assertThat(likesOf(second)).as("끊긴 동안의 좋아요는 재생되지 않는다").isEmpty();
            assertThat(second.dataEvents()).as("복구할 메시지도 없다").isEmpty();

            like(l, room);
            await().atMost(WAIT).until(() -> likesOf(second).size() == 1);
        }
    }

    @Test
    @Order(8)
    @DisplayName("[CHAT-LK-37] 깨진 pub/sub 메시지(garbage·빈 문자열·숫자 teamCode·gameId 없음·배열)는 버려지고 구독은 계속된다 — 그 뒤 정상 메시지는 전달되고 garbage 는 어떤 data 에도 새지 않는다")
    void malformedPubSub_droppedThenValidDelivered() {
        String room = room();
        TestUser s = user("OB", "p/s.jpg");
        try (E2eHttp.SseStream stream = subscribe(s, room)) {
            for (String bad : List.of("garbage", "", "{\"gameId\":\"" + room + "\",\"teamCode\":5}", "{\"teamCode\":\"HT\"}",
                    "[\"HT\"]", "{\"gameId\":\"" + room + "\",\"teamCode\":\"\"}")) {
                redis.convertAndSend("chat:likes", bad);
            }
            sleep(500);
            assertThat(likesOf(stream)).as("깨진 메시지는 전달되지 않는다").isEmpty();

            redis.convertAndSend("chat:likes", "{\"gameId\":\"" + room + "\",\"teamCode\":\"LG\"}");

            await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
            sleep(300);
            assertThat(likesOf(stream)).hasSize(1);
            assertThat(likesOf(stream).get(0).data()).isEqualTo("[\"LG\"]");
            assertThat(stream.events()).noneMatch(e -> e.data() != null && (e.data().contains("garbage") || e.data().contains("5")));
        }
    }

    @Test
    @Order(9)
    @DisplayName("[CHAT-LK-41][CHAT-LK-21] 사용자 3명이 각 1회 누르면 묶음·지연 없이 PUBLISH 3건이 나가고, 각 메시지의 teamCode 는 그 사용자의 구단 1개다")
    void threeUsersOneLikeEach_threeImmediatePublishes_singleTeamEach() {
        String room = room();
        List<TestUser> users = List.of(user("HT", "p/1"), user("LG", "p/2"), user("OB", "p/3"));

        for (TestUser u : users) {
            assertThat(like(u, room).status()).isEqualTo(202);
        }

        await().atMost(Duration.ofSeconds(3)).until(() -> publishedFor(room).size() == 3);
        sleep(500);
        assertThat(publishedFor(room)).containsExactlyInAnyOrder(
                "{\"gameId\":\"" + room + "\",\"teamCode\":\"HT\"}",
                "{\"gameId\":\"" + room + "\",\"teamCode\":\"LG\"}",
                "{\"gameId\":\"" + room + "\",\"teamCode\":\"OB\"}");
    }

    // ---------- 요청 판정 (인증 · 형식 · 순서) ----------

    @Test
    @Order(10)
    @DisplayName("[CHAT-LK-1][CHAT-LK-49] 존재하지 않는 gameId 도 형식만 맞으면 202 이고, 방 메타를 만들지 않으며 games 조회도 하지 않는다")
    void unknownGameId_202_noRoomMetaNoGameLookup() {
        TestUser l = user("HT", "p/l.jpg");
        String ghost = "NG" + (System.nanoTime() % 1_000_000_000_000L);
        long dbBefore = probe().dbSize();

        E2eHttp.Resp resp = like(l, ghost);

        assertThat(resp.status()).isEqualTo(202);
        await().atMost(WAIT).until(() -> publishedFor(ghost).size() == 1);
        assertThat(redis.hasKey("chat:room:" + ghost)).as("방 메타가 생기지 않는다").isFalse();
        assertThat(probe().dbSize()).as("Redis 키가 하나도 늘지 않는다").isEqualTo(dbBefore);
        verify(gameRepository, never()).findByNaverGameId(ghost);
        verify(gameRepository, never()).findWithDetailsByNaverGameId(ghost);
    }

    @Test
    @Order(11)
    @DisplayName("[CHAT-LK-48] gameId 가 1~20자 영숫자가 아니면 400 + ApiResponse 래퍼(data.gameId 에 위반 메시지)이고 500 이 아니다. 경계(1자·17자·20자)는 202 다")
    void gameIdFormat_400WithWrapper_boundariesAccepted() {
        TestUser l = user("HT", "p/l.jpg");
        List<String> invalid = List.of("abc-def", "A".repeat(21), "abc_def", "a.b", "a%20b", "%EA%B2%BD%EA%B8%B0");

        for (String gameId : invalid) {
            E2eHttp.Resp resp = like(l, gameId);

            assertThat(resp.status()).as(gameId + " -> " + resp.body()).isEqualTo(400);
            assertThat(resp.body()).as(gameId).contains("\"success\"");
            assertThat(resp.json().get("success").asBoolean()).as(gameId).isFalse();
            assertThat(resp.json().get("message").asString()).as(gameId).isEqualTo("입력값이 올바르지 않습니다.");
            assertThat(resp.json().get("data").get("gameId").asString()).as(gameId).isNotBlank();
        }
        for (String gameId : List.of("A", "20260809HTLG02026", "B".repeat(20), "abcXYZ0189")) {
            assertThat(like(l, gameId).status()).as(gameId).isEqualTo(202);
        }
    }

    @Test
    @Order(11)
    @DisplayName("[CHAT-LK-48] 인코딩된 세미콜론(ab%3Bcd) gameId 도 400 이다 — 단, 보안 방화벽이 컨트롤러 전에 거부해 ApiResponse 래퍼가 아니라 스프링 기본 본문이 나간다(발견 사항)")
    void encodedSemicolon_400_butFrameworkBodyNotWrapper() {
        TestUser l = user("HT", "p/l.jpg");

        E2eHttp.Resp resp = like(l, "ab%3Bcd");

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body()).doesNotContain("입력값이 올바르지 않습니다.");
    }

    @Test
    @Order(12)
    @DisplayName("[CHAT-LK-2] 미인증 + 형식 위반 gameId 는 400 이 아니라 401 이다")
    void unauthenticatedWithBadFormat_is401() {
        E2eHttp.Resp resp = http.post("/chat/rooms/abc-def/likes", null, null);

        assertThat(resp.status()).isEqualTo(401);
        assertThat(resp.json().get("message").asString()).isEqualTo("인증이 필요합니다.");
    }

    @Test
    @Order(13)
    @DisplayName("[CHAT-LK-2] 인증 + 형식 위반 + 속도 초과 상태는 202 가 아니라 400 이다")
    void authenticatedBadFormatWhileRateExceeded_is400() {
        TestUser l = user("HT", "p/l.jpg");
        String room = room();
        for (int i = 0; i < 12; i++) {
            assertThat(like(l, room).status()).isEqualTo(202);
        }

        assertThat(like(l, "bad-format").status()).isEqualTo(400);
    }

    @Test
    @Order(14)
    @DisplayName("[CHAT-LK-2] 형식 위반 요청은 응원 구단 SELECT·속도 계수·PUBLISH 를 일으키지 않는다 — 20번 보낸 뒤에도 한도(10)가 그대로 남아 있다")
    void badFormat_causesNoSelectNoRateCountNoPublish() {
        TestUser l = user("HT", "p/l.jpg");
        String room = room();
        double droppedBefore = allDropped();
        Map<String, Long> before = probe().commandCalls();

        for (int i = 0; i < 20; i++) {
            assertThat(like(l, "bad-format-" + i).status()).isEqualTo(400);
        }

        verify(userSupportTeamRepository, never()).findWithTeamByUserAccount_IdAndOpposeIsNull(l.id());
        assertThat(allDropped()).isEqualTo(droppedBefore);
        assertThat(probe().callsSince(before)).doesNotContainKey("publish");

        for (int i = 0; i < 10; i++) {
            assertThat(like(l, room).status()).isEqualTo(202);
        }
        await().atMost(WAIT).until(() -> publishedFor(room).size() == 10);
        assertThat(allDropped()).as("형식 위반 20회가 속도 한도를 쓰지 않았다").isEqualTo(droppedBefore);
    }

    @Test
    @Order(15)
    @DisplayName("[CHAT-LK-3] 헤더 없음·깨진 토큰·만료된 토큰·리프레시 토큰은 401 UNAUTHENTICATED(\"인증이 필요합니다.\")이고 발행되지 않는다")
    void invalidTokens_401() throws Exception {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        JwtProperties expired = new JwtProperties();
        expired.setSecret("e2e-secret-e2e-secret-e2e-secret-e2e-secret-0123456789");
        expired.setAccessTokenValidity(-60_000L);
        expired.setRefreshTokenValidity(7_200_000L);
        String expiredToken = new JwtTokenProvider(expired).createAccessToken(u.uid());
        String refreshToken = tokenProvider.createRefreshToken(u.uid());

        for (String token : new String[] {null, "garbage.token.value", expiredToken, refreshToken}) {
            E2eHttp.Resp resp = http.post("/chat/rooms/" + room + "/likes", token, null);

            assertThat(resp.status()).as("token=" + token).isEqualTo(401);
            assertThat(resp.json().get("success").asBoolean()).isFalse();
            assertThat(resp.json().get("data").isNull()).isTrue();
            assertThat(resp.json().get("message").asString()).isEqualTo("인증이 필요합니다.");
        }
        sleep(300);
        assertThat(publishedFor(room)).isEmpty();
    }

    @Test
    @Order(16)
    @DisplayName("[CHAT-LK-7] 본문과 Content-Type 을 읽지 않는다 — Content-Type 없음·JSON 본문·text/plain·form 본문 모두 202 이며 415·400 이 아니다")
    void bodyAndContentTypeIgnored() throws Exception {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        String path = "/chat/rooms/" + room + "/likes";

        assertThat(rawPost(path, u.token(), null, null).status()).as("Content-Type 없음").isEqualTo(202);
        assertThat(rawPost(path, u.token(), "application/json", "{\"x\":1}").status()).as("JSON 본문").isEqualTo(202);
        assertThat(rawPost(path, u.token(), "text/plain", "hello").status()).as("text/plain").isEqualTo(202);
        assertThat(rawPost(path, u.token(), "application/x-www-form-urlencoded", "a=b").status()).as("form").isEqualTo(202);
        assertThat(rawPost(path, u.token(), "application/json", "{깨진 json").status()).as("깨진 JSON 본문").isEqualTo(202);
    }

    // ---------- 응원 구단 · 속도 제한 · 저장 안 함 ----------

    @Test
    @Order(20)
    @DisplayName("[CHAT-LK-8][CHAT-LK-11][CHAT-LK-16][CHAT-LK-18] 한 사용자가 100번 눌러도 SELECT 1회, 발행 10회(나머지 90은 rate-limit 으로 버림), Redis 는 PUBLISH 외 명령·키를 쓰지 않고 Kafka 레코드도 0이다")
    void hundredLikes_storesNothing_oneSelect_tenPublishes() {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        awaitLikesSubscribed();
        Map<String, Long> commandsBefore = probe().commandCalls();
        long keysBefore = probe().dbSize();
        Map<TopicPartition, Long> kafkaBefore = kafkaEnds();
        double rateLimitedBefore = dropped("rate-limit");

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(like(u, room).status()).isEqualTo(202);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        await().atMost(WAIT).until(() -> publishedFor(room).size() >= 10);
        sleep(500);

        int expectedPublishes = 10;
        if (elapsedMs < 950) {
            assertThat(publishedFor(room)).as("한 창(1초) 안에 끝났으면 정확히 10건").hasSize(expectedPublishes);
            assertThat(dropped("rate-limit") - rateLimitedBefore).isEqualTo(90.0);
        } else {
            assertThat(publishedFor(room).size()).as("느려서 창이 넘어갔으면 창마다 10건").isBetween(10, 10 * (int) (elapsedMs / 1000 + 1));
        }
        verify(userSupportTeamRepository, times(1)).findWithTeamByUserAccount_IdAndOpposeIsNull(u.id());
        assertThat(probe().callsSince(commandsBefore).keySet()).as("좋아요 경로가 일으킨 Redis 명령").isSubsetOf(ALLOWED_COMMANDS);
        assertThat(probe().dbSize()).as("Redis 키 증가 없음(캐시 키·속도 키·방 키 없음)").isEqualTo(keysBefore);
        assertThat(kafkaEnds()).as("chat-messages / chat-control 레코드 증가 없음").isEqualTo(kafkaBefore);
        assertThat(publishedFor(room)).allMatch(m -> m.equals("{\"gameId\":\"" + room + "\",\"teamCode\":\"HT\"}"));
    }

    @Test
    @Order(21)
    @DisplayName("[CHAT-LK-16] 같은 초 15회 → 15회 전부 202(429 아님), PUBLISH 10회, rate-limit +5")
    void fifteenInOneSecond_allAccepted_tenPublished() {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        double before = dropped("rate-limit");

        long start = System.nanoTime();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            statuses.add(like(u, room).status());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        await().atMost(WAIT).until(() -> publishedFor(room).size() >= 10);
        sleep(400);

        assertThat(statuses).containsOnly(202);
        if (elapsedMs < 950) {
            assertThat(publishedFor(room)).hasSize(10);
            assertThat(dropped("rate-limit") - before).isEqualTo(5.0);
        }
    }

    @Test
    @Order(22)
    @DisplayName("[CHAT-LK-17] 속도 창은 경기를 가리지 않고 공유된다(A경기 6 + B경기 6 → 10건 발행) — 메시지 전송 한도와는 따로 센다(좋아요 10회 뒤 메시지 전송 202)")
    void rateWindowSharedAcrossGames_separateFromMessageLimit() {
        TestUser u = user("HT", "p/u.jpg");
        String a = room();
        String b = room();
        double before = dropped("rate-limit");

        long start = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            like(u, a);
        }
        for (int i = 0; i < 6; i++) {
            like(u, b);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        await().atMost(WAIT).until(() -> publishedFor(a).size() + publishedFor(b).size() >= 10);
        sleep(400);

        if (elapsedMs < 950) {
            assertThat(publishedFor(a)).hasSize(6);
            assertThat(publishedFor(b)).hasSize(4);
            assertThat(dropped("rate-limit") - before).isEqualTo(2.0);
        }
    }

    @Test
    @Order(23)
    @DisplayName("[CHAT-LK-13] 응원 구단이 없는 계정의 좋아요는 202 이고 발행되지 않으며 no-team +1 이다")
    void noTeamAccount_202_noPublish_noTeamMetric() {
        TestUser noTeam = user(null, "p/n.jpg");
        String room = room();
        double before = dropped("no-team");

        assertThat(like(noTeam, room).status()).isEqualTo(202);

        sleep(400);
        assertThat(publishedFor(room)).isEmpty();
        assertThat(dropped("no-team") - before).isEqualTo(1.0);
    }

    @Test
    @Order(24)
    @DisplayName("[CHAT-LK-14] 빈 조회는 캐시되지 않는다 — 응원 구단이 없던 계정이 구단을 설정한 직후의 좋아요는 5분을 기다리지 않고 그 코드로 발행된다")
    void emptyLookupNotCached_nextLikeUsesNewTeamImmediately() {
        TestUser u = user(null, "p/n.jpg");
        String room = room();
        like(u, room);
        sleep(300);
        assertThat(publishedFor(room)).isEmpty();

        restubTeam(u, "KT");
        assertThat(like(u, room).status()).isEqualTo(202);

        await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);
        assertThat(publishedFor(room).get(0)).contains("\"teamCode\":\"KT\"");
    }

    @Test
    @Order(25)
    @DisplayName("[CHAT-LK-12] 구단을 바꿔도 캐시(기본 300초) 안에서는 옛 코드로 발행된다")
    void teamChangeNotVisibleWithinCacheTtl() {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        like(u, room);
        await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);

        restubTeam(u, "LG");
        sleep(1_100);
        like(u, room);

        await().atMost(WAIT).until(() -> publishedFor(room).size() == 2);
        assertThat(publishedFor(room)).allMatch(m -> m.contains("\"teamCode\":\"HT\""));
    }

    @Test
    @Order(26)
    @DisplayName("[CHAT-LK-15] DB 조회가 실패하면 202 + 발행 없음 + team-lookup-failed +1 이고, 이미 캐시에 든 사용자는 영향 없이 발행된다")
    void dbFailure_dropsUncachedUser_cachedUserUnaffected() {
        TestUser cached = user("HT", "p/c.jpg");
        TestUser uncached = user("LG", "p/u.jpg");
        String room = room();
        like(cached, room);
        await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);
        given(userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(anyLong()))
                .willThrow(new IllegalStateException("DB down"));
        double before = dropped("team-lookup-failed");

        assertThat(like(uncached, room).status()).isEqualTo(202);
        sleep(300);
        assertThat(publishedFor(room)).as("캐시에 없던 사용자의 좋아요는 버려진다").hasSize(1);
        assertThat(dropped("team-lookup-failed") - before).isEqualTo(1.0);

        sleep(1_000);
        assertThat(like(cached, room).status()).isEqualTo(202);
        await().atMost(WAIT).until(() -> publishedFor(room).size() == 2);
    }

    @Test
    @Order(27)
    @DisplayName("[CHAT-LK-23] 메트릭 chat.likes.publish.failed 와 chat.likes.dropped{reason} 가 0 부터 actuator 에서 조회된다 (인증 필수)")
    void metricsVisibleFromZeroViaActuator() {
        TestUser u = user("HT", "p/u.jpg");

        assertThat(http.get("/chat/actuator/metrics/chat.likes.publish.failed", null).status()).isEqualTo(401);
        E2eHttp.Resp failed = http.get("/chat/actuator/metrics/chat.likes.publish.failed", u.token());
        assertThat(failed.status()).isEqualTo(200);
        assertThat(failed.json().get("measurements").get(0).get("value").asDouble()).isEqualTo(publishFailed());
        for (String reason : List.of("rate-limit", "no-team", "team-lookup-failed", "queue-full")) {
            E2eHttp.Resp resp = http.get("/chat/actuator/metrics/chat.likes.dropped?tag=reason:" + reason, u.token());
            assertThat(resp.status()).as(reason).isEqualTo(200);
            assertThat(resp.json().get("measurements").get(0).get("value").asDouble()).as(reason).isEqualTo(dropped(reason));
        }
    }

    // ---------- 회귀 · 메시지 경로와의 공존 (Kafka 를 쓰므로 뒤에 둔다) ----------

    @Test
    @Order(30)
    @DisplayName("[CHAT-LK-17] 같은 초에 좋아요 10회를 보낸 사용자의 메시지 전송은 429 가 아니라 202 다")
    void afterTenLikes_messageSendStill202() {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        for (int i = 0; i < 10; i++) {
            like(u, room);
        }

        E2eHttp.Resp resp = send(u, room, "좋아요를 많이 누른 뒤 메시지", newClientMsgId());

        assertThat(resp.status()).isEqualTo(202);
    }

    @Test
    @Order(31)
    @DisplayName("[CHAT-LK-17] 메시지를 3번(전송 한도) 보낸 직후의 좋아요도 정상 발행된다")
    void afterThreeMessages_likeStillPublished() {
        TestUser u = user("HT", "p/u.jpg");
        String room = room();
        for (int i = 0; i < 3; i++) {
            sendOk(u, room, "메시지 " + i);
        }

        assertThat(like(u, room).status()).isEqualTo(202);

        await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);
    }

    @Test
    @Order(32)
    @DisplayName("[CHAT-LK-33][CHAT-LK-28] 좋아요가 쏟아지는 방에서도 :connected 첫 프레임, messages 의 id 규칙, :ping 하트비트가 그대로이고 likes 는 한 번도 id 를 갖지 않는다")
    void floodDoesNotDisturbExistingEvents() throws Exception {
        String room = room();
        TestUser s = user("OB", "p/s.jpg");
        TestUser talker = user("LG", "p/t.jpg");
        List<TestUser> likers = List.of(user("HT", "p/1"), user("SK", "p/2"), user("KT", "p/3"));
        openRoom(s, room);
        try (E2eHttp.SseStream stream = subscribe(s, room)) {
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                sendOk(talker, room, "준비 확인");
                return stream.dataEvents().stream().anyMatch(e -> "messages".equals(e.name()));
            });
            ExecutorService pool = Executors.newFixedThreadPool(likers.size());
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (TestUser liker : likers) {
                futures.add(pool.submit(() -> {
                    while (System.nanoTime() - start < 17_000_000_000L) {
                        like(liker, room);
                        sleep(110);
                    }
                    return null;
                }));
            }
            sleep(1_000);
            long msgId = msgIdOf(sendOk(talker, room, "좋아요 폭주 중의 메시지"));
            for (Future<?> f : futures) {
                f.get(40, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            sleep(400);

            List<E2eHttp.SseEvent> events = stream.events();
            assertThat(events.get(0).comment()).as("첫 프레임").isEqualTo("connected");
            assertThat(events).as(":ping 하트비트 15초").anyMatch(e -> "ping".equals(e.comment()));
            assertThat(events).filteredOn(e -> "messages".equals(e.name()))
                    .anySatisfy(e -> {
                        assertThat(e.id()).isEqualTo(String.valueOf(msgId));
                        assertThat(e.data()).contains("좋아요 폭주 중의 메시지");
                    });
            assertThat(likesOf(stream)).isNotEmpty().allMatch(e -> e.id() == null);
            assertThat(likesOf(stream).size()).isLessThanOrEqualTo((int) (1 + (elapsedMs + 400) / 100));
            assertThat(events).filteredOn(e -> e.name() != null).extracting(E2eHttp.SseEvent::name)
                    .containsOnly("messages", "likes");
        }
    }
}
