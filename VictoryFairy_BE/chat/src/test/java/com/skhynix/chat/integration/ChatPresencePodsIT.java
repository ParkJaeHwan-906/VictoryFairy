package com.skhynix.chat.integration;

import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

import com.skhynix.chat.gateway.service.PresenceStore;
import com.skhynix.chat.gateway.service.PresenceTracker;
import com.skhynix.chat.global.config.ChatPresenceProperties;
import com.skhynix.chat.integration.E2eHttp.SseStream;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.domain.game.entity.Game;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 파드가 둘 이상일 때의 접속 인원. 스프링 테스트 캐시 밖에서 앱을 직접 띄운다(AppLauncher) — 같은 Redis·Kafka 를 보는 서로
 * 다른 앱 인스턴스이고 instanceId 도 다르다. 기본 설정 파드 2개를 클래스 전체가 공유하고, 설정을 바꾸는 테스트만 자기 파드를 따로 띄운다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChatPresencePodsIT {

    private static final Duration WITHIN_BROADCAST = Duration.ofSeconds(6);

    private static AppLauncher pod1;
    private static AppLauncher pod2;
    private static StringRedisTemplate redis;
    private static LikesProbe probe;
    private static long nextId = 70_000;

    @BeforeAll
    static void startPods() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        pod1 = new AppLauncher().start();
        pod2 = new AppLauncher().start();
        probe = new LikesProbe();
        redis = probe.template;
    }

    @AfterAll
    static void stopPods() {
        if (probe != null) {
            probe.close();
        }
        if (pod2 != null) {
            pod2.close();
        }
        if (pod1 != null) {
            pod1.close();
        }
    }

    // ---------- 도구 ----------

    private static synchronized long newUser() {
        return ++nextId;
    }

    private static String registryId(AppLauncher pod) {
        return pod.context.getBean(SseEmitterRegistry.class).instanceId();
    }

    /** 모든 파드가 같은 gameId 를 오늘 경기로 안다. */
    private static String sharedRoom(AppLauncher... pods) {
        String gameId = "PODS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Game today = game(gameId, new ChatClock().today().atTime(18, 30), "SCHEDULED");
        for (AppLauncher pod : pods) {
            given(pod.gameRepository.findActiveByNaverGameId(gameId)).willReturn(Optional.of(today));
            given(pod.gameRepository.findWithDetailsByNaverGameId(gameId)).willReturn(Optional.of(today));
        }
        return gameId;
    }

    private static SseStream open(AppLauncher pod, long userId, String room) {
        String token = pod.userToken(userId);
        SseStream stream = pod.http().subscribe("/chat/rooms/" + room + "/subscribe", token, null);
        SseEmitterRegistry registry = pod.context.getBean(SseEmitterRegistry.class);
        await().atMost(Duration.ofSeconds(20)).until(() ->
                registry.subscriptions(room).stream().anyMatch(s -> userId == s.userAccountId()));
        return stream;
    }

    private static List<Long> counts(SseStream stream) {
        return stream.presenceEvents().stream().map(e -> E2eHttp.MAPPER.readTree(e.data()).get("count").asLong()).toList();
    }

    private static long last(SseStream stream) {
        List<Long> counts = counts(stream);
        return counts.isEmpty() ? -1 : counts.get(counts.size() - 1);
    }

    private static void awaitCount(SseStream stream, long expected) {
        await().atMost(WITHIN_BROADCAST).untilAsserted(() ->
                assertThat(last(stream)).as("받은 presence 값들: " + counts(stream)).isEqualTo(expected));
    }

    private static Double score(String room, long user) {
        return redis.opsForZSet().score("chat:presence:" + room, String.valueOf(user));
    }

    private static Object owner(String room, long user) {
        return redis.opsForHash().get("chat:presence-owner:" + room, String.valueOf(user));
    }

    // ---------- 다중 파드 합산 ----------

    @Test
    @DisplayName("[CHAT-PR-2] 파드 1 에 A, 파드 2 에 B 가 같은 방을 구독하면 두 구독자 모두 파드 로컬 값 1이 아니라 합산 값 2를 받는다")
    void countIsSummedAcrossPods() {
        String room = sharedRoom(pod1, pod2);
        long a = newUser();
        long b = newUser();

        try (SseStream sa = open(pod1, a, room); SseStream sb = open(pod2, b, room)) {
            awaitCount(sa, 2);
            awaitCount(sb, 2);
            assertThat(pod1.context.getBean(SseEmitterRegistry.class).count(room)).as("파드 로컬 연결 수는 1").isEqualTo(1);
            assertThat(counts(sb)).as("B 에게 로컬 값 1 이 간 적이 없다").doesNotContain(1L);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-5] 각 구독의 소유자 기록은 그 구독이 붙은 파드의 instanceId 다")
    void ownerIsThePodTheSubscriptionLandedOn() {
        String room = sharedRoom(pod1, pod2);
        long a = newUser();
        long b = newUser();

        try (SseStream sa = open(pod1, a, room); SseStream sb = open(pod2, b, room)) {
            awaitCount(sa, 2);

            assertThat(owner(room, a)).isEqualTo(registryId(pod1));
            assertThat(owner(room, b)).isEqualTo(registryId(pod2));
            assertThat(registryId(pod1)).isNotEqualTo(registryId(pod2));
        }
    }

    @Test
    @DisplayName("[CHAT-PR-18] 구독자가 두 파드에 나뉜 방에서 입장이 멈추고 집계 주기가 지나면 모든 구독자의 마지막 presence 값이 같다")
    void podsConvergeOnSameLastValue() {
        String room = sharedRoom(pod1, pod2);
        long a = newUser();
        long b = newUser();
        long c = newUser();
        long d = newUser();

        try (SseStream sa = open(pod1, a, room); SseStream sb = open(pod2, b, room);
                SseStream sc = open(pod1, c, room); SseStream sd = open(pod2, d, room)) {
            await().atMost(WITHIN_BROADCAST).untilAsserted(() -> {
                assertThat(last(sa)).as(counts(sa).toString()).isEqualTo(4L);
                assertThat(last(sb)).as(counts(sb).toString()).isEqualTo(4L);
                assertThat(last(sc)).as(counts(sc).toString()).isEqualTo(4L);
                assertThat(last(sd)).as(counts(sd).toString()).isEqualTo(4L);
            });
        }
    }

    @Test
    @DisplayName("[CHAT-PR-9] 계정 A 가 파드 1 에서 구독 중 파드 2 로 재구독하면 파드 1 의 옛 구독이 축출로 닫혀도 파드 1 의 다른 구독자가 받는 값은 그대로이고 1 적은 프레임도 없으며 소유자는 파드 2 다")
    void reSubscribeOnOtherPodKeepsCountAndTransfersOwnership() throws Exception {
        String room = sharedRoom(pod1, pod2);
        long a = newUser();
        long b = newUser();

        try (SseStream oldA = open(pod1, a, room); SseStream observer = open(pod1, b, room)) {
            awaitCount(observer, 2);

            try (SseStream newA = open(pod2, a, room)) {
                await().atMost(Duration.ofSeconds(20)).alias("옛 구독이 chat-control 축출 명령으로 닫힌다").until(oldA::isEnded);
                Thread.sleep(5_000);   // 집계 주기 두 번 이상

                assertThat(counts(observer)).as("관찰자가 받은 값").containsExactly(2L);
                assertThat(score(room, a)).as("A 는 인원에 남아 있다").isNotNull();
                assertThat(owner(room, a)).isEqualTo(registryId(pod2));
                assertThat(last(newA)).isEqualTo(2L);
            }
        }
    }

    @Test
    @DisplayName("[CHAT-PR-8] 파드를 정상 종료하면 그 파드의 구독 계정이 만료 창(45초)을 기다리지 않고 즉시 빠지고, 다른 파드의 구독자가 곧 감소한 값을 받는다")
    void gracefulPodShutdownRemovesItsAccountsAtOnce() throws Exception {
        long survivor = newUser();
        long leaver = newUser();
        AppLauncher dying = new AppLauncher().start();
        String room = sharedRoom(pod1, dying);
        SseStream observer = open(pod1, survivor, room);
        try {
            open(dying, leaver, room);
            awaitCount(observer, 2);

            long start = System.nanoTime();
            dying.close();
            Thread.sleep(3_000);
            awaitCount(observer, 1);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("45초 만료가 아니라 종료 처리로").isLessThan(20_000);
            assertThat(score(room, leaver)).isNull();
            assertThat(owner(room, leaver)).isNull();
        } finally {
            observer.close();
            dying.close();
        }
    }

    // ---------- 설정 키 ----------

    @Test
    @DisplayName("[CHAT-PR-29] 설정 없이 기동하면 expire-seconds=45, broadcast-interval-ms=2000, key-ttl-seconds=90 이다")
    void defaultsMatchTable() {
        ChatPresenceProperties properties = pod1.context.getBean(ChatPresenceProperties.class);

        assertThat(properties.expireSeconds()).isEqualTo(45L);
        assertThat(properties.broadcastIntervalMs()).isEqualTo(2000L);
        assertThat(properties.keyTtlSeconds()).isEqualTo(90L);
    }

    @Test
    @DisplayName("[CHAT-PR-29] 세 키를 덮어쓰면(relaxed binding) 반영된다 — 500ms 주기로 새 값 수신, 서버 시각 -21초 member 가 20초 만료로 빠짐, 키 TTL 이 30초 이하")
    void overridesAreHonored() {
        try (AppLauncher tuned = new AppLauncher()) {
            tuned.start("--chat.presence.broadcast-interval-ms=500", "--chat.presence.expire-seconds=20",
                    "--chat.presence.key-ttl-seconds=30");
            ChatPresenceProperties properties = tuned.context.getBean(ChatPresenceProperties.class);
            assertThat(properties.broadcastIntervalMs()).isEqualTo(500L);
            assertThat(properties.expireSeconds()).isEqualTo(20L);
            assertThat(properties.keyTtlSeconds()).isEqualTo(30L);
            String room = sharedRoom(tuned);
            long a = newUser();
            long b = newUser();

            try (SseStream sa = open(tuned, a, room)) {
                await().atMost(Duration.ofSeconds(3)).until(() -> last(sa) == 1);
                try (SseStream sb = open(tuned, b, room)) {
                    await().atMost(Duration.ofMillis(1_500)).until(() -> last(sa) == 2);
                }
                assertThat(redis.getExpire("chat:presence:" + room)).isBetween(1L, 30L);
                assertThat(redis.getExpire("chat:presence-owner:" + room)).isBetween(1L, 30L);

                long redisNow = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time(TimeUnit.MILLISECONDS));
                redis.opsForZSet().add("chat:presence:" + room, "990100", redisNow - 21_000);
                redis.opsForHash().put("chat:presence-owner:" + room, "990100", "dead-pod");
                await().atMost(Duration.ofSeconds(5)).until(() -> score(room, 990100L) == null);
            }
        }
    }

    // ---------- 역할 ----------

    @Test
    @DisplayName("[CHAT-PR-28] 게이트웨이 역할을 끈 파드(history-writer 전용)에는 PresenceStore·PresenceTracker 빈이 없고 presence 키 쓰기 명령이 없다")
    void gatewayRoleOffHasNoPresenceBeansNorKeyWrites() throws Exception {
        try (AppLauncher writerOnly = new AppLauncher(); RedisMonitor monitor = new RedisMonitor()) {
            java.util.Set<String> before = clientAddresses();
            int mark = monitor.mark();
            writerOnly.start("--chat.role.api=false", "--chat.role.gateway=false");

            Thread.sleep(5_000);   // 집계 주기 두 번 이상

            // 같은 JVM 의 다른 파드가 낸 명령과 섞이지 않게, 이 파드가 새로 연 연결의 주소로 가른다
            java.util.Set<String> mine = clientAddresses();
            mine.removeAll(before);
            assertThat(writerOnly.context.getBeansOfType(PresenceStore.class)).isEmpty();
            assertThat(writerOnly.context.getBeansOfType(PresenceTracker.class)).isEmpty();
            assertThat(writerOnly.context.getBeansOfType(SseEmitterRegistry.class)).isEmpty();
            assertThat(monitor.since(mark)).filteredOn(e -> mine.stream().anyMatch(addr -> e.raw().contains("[0 " + addr + "]")))
                    .noneMatch(e -> e.touches("chat:presence"));
        }
    }

    private static java.util.Set<String> clientAddresses() {
        var list = redis.execute((RedisCallback<java.util.List<org.springframework.data.redis.core.types.RedisClientInfo>>) c ->
                c.serverCommands().getClientList());
        java.util.Set<String> result = new java.util.HashSet<>();
        if (list != null) {
            list.forEach(i -> result.add(i.getAddressPort()));
        }
        return result;
    }
}
