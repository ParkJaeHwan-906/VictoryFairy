package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.skhynix.chat.like.service.SupportTeamChangedSubscriber;
import com.skhynix.chat.like.service.SupportTeamCodeCache;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.team.entity.Team;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 응원 구단 캐시 무효화(support:team-changed) 통합 검증. 실 Redis 에 직접 발행하고(= user 앱의 신호 흉내),
 * "SELECT" 는 앱의 목 {@code UserSupportTeamRepository} 호출 수로 센다. 발행된 좋아요는 {@link LikesProbe} 로 본다.
 *
 * <p>무효화 처리는 비동기(Lettuce I/O 스레드)이므로 "신호 뒤 좋아요" 는 반영될 때까지 폴링한다 — sleep 에 기대지 않는다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ChatLikesTeamChangedIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String CHANNEL = SupportTeamChangedSubscriber.CHANNEL;
    private static final AtomicLong IDS = new AtomicLong(9_700);
    private static LikesProbe probe;

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

    private static void prepareBroker() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
    }

    private record Liker(long id, String token) {
    }

    /** start() 전에 불러야 한다 — 토큰 발급이 목 리포지토리를 채운다. */
    private static Liker liker(AppLauncher app, String teamCode) {
        return liker(app, IDS.incrementAndGet(), teamCode);
    }

    private static Liker liker(AppLauncher app, long id, String teamCode) {
        String token = app.userToken(id);
        restub(app, id, teamCode);
        return new Liker(id, token);
    }

    private static void restub(AppLauncher app, long id, String teamCode) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(teamCode);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        doReturn(Optional.of(support)).when(app.userSupportTeamRepository).findWithTeamByUserAccount_IdAndOpposeIsNull(id);
    }

    private static int like(AppLauncher app, Liker liker, String room) {
        return app.http().post("/chat/rooms/" + room + "/likes", liker.token(), null).status();
    }

    private static List<String> publishedFor(String gameId) {
        return probe().published().stream().filter(m -> m.contains("\"" + gameId + "\"")).toList();
    }

    private static boolean publishedCode(String room, String code) {
        return publishedFor(room).stream().anyMatch(m -> m.contains("\"teamCode\":\"" + code + "\""));
    }

    /** 그 방에 {@code code} 로 발행된 좋아요가 나타날 때까지 같은 사용자로 좋아요를 반복한다(속도 제한 10/s 아래). */
    private static void likeUntilPublished(AppLauncher app, Liker liker, String room, String code) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).until(() -> {
            assertThat(like(app, liker, room)).isEqualTo(202);
            return publishedCode(room, code);
        });
    }

    private static void awaitSubscribers(long expected) {
        await().atMost(Duration.ofSeconds(45)).until(() -> probe().numsub(CHANNEL) == expected);
    }

    /** 그 사용자 id 로 앱의 목 리포지토리가 받은 조회 수. clearInvocations 이후부터 센다. */
    private static long selects(AppLauncher app, long userId) {
        return mockingDetails(app.userSupportTeamRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("findWithTeamByUserAccount_IdAndOpposeIsNull"))
                .filter(i -> i.getArguments()[0].equals(userId))
                .count();
    }

    // ---------- LK-53 / LK-54 / LK-12 ----------

    @Test
    @DisplayName("[CHAT-LK-12][CHAT-LK-53][CHAT-LK-54] API 파드 2개: 신호 1건이 두 파드의 그 사용자 캐시만 지워 다음 좋아요가 새 구단(LG)으로 나가고, 다른 사용자의 캐시는 남는다")
    void signalInvalidatesThatUserOnEveryApiPod() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (AppLauncher podA = new AppLauncher(); AppLauncher podB = new AppLauncher()) {
            Liker onA = liker(podA, "HT");
            Liker onB = liker(podB, onA.id(), "HT");
            Liker other = liker(podA, "OB");
            podA.start();
            podB.start();
            awaitSubscribers(baseline + 2);
            String roomA = podA.room();
            String roomB = podB.room();
            String roomOther = podA.room();
            assertThat(like(podA, onA, roomA)).isEqualTo(202);
            assertThat(like(podB, onB, roomB)).isEqualTo(202);
            assertThat(like(podA, other, roomOther)).isEqualTo(202);
            await().atMost(WAIT).until(() -> publishedCode(roomA, "HT") && publishedCode(roomB, "HT")
                    && publishedCode(roomOther, "OB"));
            clearInvocations(podA.userSupportTeamRepository, podB.userSupportTeamRepository);
            restub(podA, onA.id(), "LG");
            restub(podB, onA.id(), "LG");

            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + onA.id() + "}");

            likeUntilPublished(podA, onA, roomA, "LG");
            likeUntilPublished(podB, onB, roomB, "LG");
            assertThat(selects(podA, onA.id())).as("신호 뒤 첫 좋아요만 SELECT").isEqualTo(1);
            assertThat(selects(podB, onA.id())).isEqualTo(1);
            assertThat(like(podA, other, roomOther)).isEqualTo(202);
            assertThat(selects(podA, other.id())).as("다른 사용자의 캐시 항목은 남는다").isZero();
        }
    }

    @Test
    @DisplayName("[CHAT-LK-53] 캐시에 없는 id 의 신호는 오류 없이 지나가고 다른 사용자의 캐시를 건드리지 않는다")
    void signalForUnknownUser_isHarmless() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (AppLauncher app = new AppLauncher()) {
            Liker cached = liker(app, "HT");
            Liker sentinel = liker(app, "LG");
            app.start();
            awaitSubscribers(baseline + 1);
            String room = app.room();
            String sentinelRoom = app.room();
            like(app, cached, room);
            like(app, sentinel, sentinelRoom);
            await().atMost(WAIT).until(() -> publishedCode(room, "HT") && publishedCode(sentinelRoom, "LG"));
            clearInvocations(app.userSupportTeamRepository);

            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + (IDS.incrementAndGet() + 100_000) + "}");
            // 같은 구독 연결로 순서대로 처리되므로 sentinel 신호가 반영되면 앞선 신호도 처리가 끝난 것이다
            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + sentinel.id() + "}");
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
                like(app, sentinel, sentinelRoom);
                return selects(app, sentinel.id()) >= 1;
            });

            assertThat(like(app, cached, room)).isEqualTo(202);
            assertThat(selects(app, cached.id())).isZero();
        }
    }

    // ---------- LK-57 ----------

    @Test
    @DisplayName("[CHAT-LK-57] 형식 위반 신호(비JSON·문자열·음수)는 버려지고 캐시 전체가 지워지지 않으며, 뒤이은 정상 신호는 처리된다")
    void malformedSignals_dropped_thenValidStillWorks() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (AppLauncher app = new AppLauncher()) {
            Liker target = liker(app, "HT");
            Liker bystander = liker(app, "OB");
            app.start();
            awaitSubscribers(baseline + 1);
            String targetRoom = app.room();
            String bystanderRoom = app.room();
            like(app, target, targetRoom);
            like(app, bystander, bystanderRoom);
            await().atMost(WAIT).until(() -> publishedCode(targetRoom, "HT") && publishedCode(bystanderRoom, "OB"));
            clearInvocations(app.userSupportTeamRepository);
            restub(app, target.id(), "LG");

            probe().publishRaw(CHANNEL, "garbage");
            probe().publishRaw(CHANNEL, "{\"userAccountId\":\"abc\"}");
            probe().publishRaw(CHANNEL, "{\"userAccountId\":-1}");
            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + target.id() + "}");

            likeUntilPublished(app, target, targetRoom, "LG");
            assertThat(selects(app, target.id())).isEqualTo(1);
            assertThat(like(app, bystander, bystanderRoom)).isEqualTo(202);
            assertThat(selects(app, bystander.id())).as("깨진 신호가 전체를 지우지 않았다").isZero();
        }
    }

    // ---------- LK-54 / LK-55 ----------

    @Test
    @DisplayName("[CHAT-LK-54][CHAT-LK-55] 기본 역할 파드는 support:team-changed 구독자를 정확히 1 늘리고, API 역할을 끈 파드는 구독하지 않으며 구독 빈과 캐시 빈이 없다")
    void apiRoleOffPod_doesNotSubscribe() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (AppLauncher full = new AppLauncher()) {
            full.start();
            awaitSubscribers(baseline + 1);
            assertThat(full.context.getBeansOfType(SupportTeamChangedSubscriber.class)).hasSize(1);
            assertThat(full.context.getBeansOfType(SupportTeamCodeCache.class)).hasSize(1);

            try (AppLauncher gatewayOnly = new AppLauncher()) {
                gatewayOnly.start("--chat.role.api=false", "--chat.role.history-writer=false");

                await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                        .until(() -> probe().numsub(CHANNEL) == baseline + 1);
                assertThat(gatewayOnly.context.getBeansOfType(SupportTeamChangedSubscriber.class)).isEmpty();
                assertThat(gatewayOnly.context.getBeansOfType(SupportTeamCodeCache.class)).isEmpty();
            }
        }
        awaitSubscribers(baseline);
    }

    // ---------- LK-58 ----------

    @Test
    @DisplayName("[CHAT-LK-58] Redis 가 죽은 채 기동해도 앱은 뜨고 캐시에 든 사용자의 좋아요 판정(SELECT 없음)은 영향이 없으며, Redis 가 나타나면 30초 안에 구독한다")
    void redisDeadAtBoot_startsAndSubscribesLater() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = new AppLauncher()) {
            app.redisHostOverride = proxy.host();
            app.redisPortOverride = proxy.port();
            Liker liker = liker(app, "HT");

            app.start();

            String room = app.room();
            assertThat(like(app, liker, room)).isEqualTo(202);
            assertThat(like(app, liker, room)).isEqualTo(202);
            assertThat(selects(app, liker.id())).as("Redis 없이도 캐시는 동작").isEqualTo(1);
            assertThat(probe().numsub(CHANNEL)).isEqualTo(baseline);

            long upAt = System.nanoTime();
            proxy.set(RedisProxy.Mode.UP);
            awaitSubscribers(baseline + 1);

            assertThat((System.nanoTime() - upAt) / 1_000_000).as("복구 뒤 구독까지(ms)").isLessThanOrEqualTo(30_000L);
        }
    }

    // ---------- LK-59 ----------

    @Test
    @DisplayName("[CHAT-LK-59] 끊겼던 구독이 다시 맺어지면 그 파드의 캐시 전체를 비운다 — 연결이 끊긴 사이 DB 가 바뀐 두 사용자 모두 다음 좋아요에서 SELECT 1회 후 새 구단(LG)으로 나간다")
    void resubscribe_clearsWholeCache() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = new AppLauncher()) {
            app.redisHostOverride = proxy.host();
            app.redisPortOverride = proxy.port();
            proxy.set(RedisProxy.Mode.UP);
            Liker a = liker(app, "HT");
            Liker b = liker(app, "OB");
            app.start();
            awaitSubscribers(baseline + 1);
            String roomA = app.room();
            String roomB = app.room();
            likeUntilPublished(app, a, roomA, "HT");
            likeUntilPublished(app, b, roomB, "OB");
            restub(app, a.id(), "LG");
            restub(app, b.id(), "LG");
            clearInvocations(app.userSupportTeamRepository);

            proxy.set(RedisProxy.Mode.DOWN);
            awaitSubscribers(baseline);
            proxy.set(RedisProxy.Mode.UP);
            awaitSubscribers(baseline + 1);

            likeUntilPublished(app, a, roomA, "LG");
            likeUntilPublished(app, b, roomB, "LG");
            assertThat(selects(app, a.id())).isEqualTo(1);
            assertThat(selects(app, b.id())).isEqualTo(1);
        }
    }

    // ---------- LK-60 ----------

    @Test
    @DisplayName("[CHAT-LK-60] 캐시 미스 조회 중 단건 신호가 처리되면 그 요청은 읽은 값(HT)으로 발행되되 캐시에 남지 않아 다음 좋아요가 SELECT 를 다시 치고 DB 값(LG)으로 나간다. 다른 사용자 항목은 영향 없다")
    void signalDuringInFlightLookup_notCached() throws Exception {
        prepareBroker();
        long baseline = probe().numsub(CHANNEL);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (AppLauncher app = new AppLauncher()) {
            Liker slow = liker(app, "HT");
            Liker sentinel = liker(app, "LG");
            Liker bystander = liker(app, "OB");
            app.start();
            awaitSubscribers(baseline + 1);
            String slowRoom = app.room();
            String sentinelRoom = app.room();
            String bystanderRoom = app.room();
            like(app, sentinel, sentinelRoom);
            like(app, bystander, bystanderRoom);
            await().atMost(WAIT).until(() -> publishedCode(sentinelRoom, "LG") && publishedCode(bystanderRoom, "OB"));
            clearInvocations(app.userSupportTeamRepository);

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Optional<UserSupportTeam> read = mockSupport("HT");
            given(app.userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(slow.id())).willAnswer(inv -> {
                entered.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                return read;
            });
            Future<Integer> inFlight = pool.submit(() -> like(app, slow, slowRoom));
            assertThat(entered.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("조회가 DB 에서 대기 중").isTrue();

            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + slow.id() + "}");
            // 같은 연결 순서 보장: sentinel 의 무효화가 관측되면 slow 신호는 이미 처리됐다
            probe().publishRaw(CHANNEL, "{\"userAccountId\":" + sentinel.id() + "}");
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
                like(app, sentinel, sentinelRoom);
                return selects(app, sentinel.id()) >= 1;
            });
            release.countDown();

            assertThat(inFlight.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(202);
            await().atMost(WAIT).until(() -> publishedCode(slowRoom, "HT"));
            restub(app, slow.id(), "LG");
            likeUntilPublished(app, slow, slowRoom, "LG");
            assertThat(selects(app, slow.id())).as("진행 중이던 조회 1회 + 적재 안 됐으므로 재조회 1회").isEqualTo(2);
            assertThat(like(app, bystander, bystanderRoom)).isEqualTo(202);
            assertThat(selects(app, bystander.id())).as("다른 사용자 항목은 영향 없다").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Optional<UserSupportTeam> mockSupport(String code) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(code);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        return Optional.of(support);
    }

    // ---------- LK-14 ----------

    @Test
    @DisplayName("[CHAT-LK-14] 응원 행이 없던 계정이 구단을 설정하면 신호 없이도 TTL 을 기다리지 않고 다음 좋아요가 그 구단 코드로 나간다")
    void firstTeamSet_withoutSignal_reflectsImmediately() throws Exception {
        prepareBroker();
        try (AppLauncher app = new AppLauncher()) {
            long id = IDS.incrementAndGet();
            String token = app.userToken(id); // 응원 행 없음(empty)
            app.start();
            String room = app.room();
            Liker liker = new Liker(id, token);

            assertThat(like(app, liker, room)).isEqualTo(202);
            assertThat(publishedFor(room)).as("응원 행이 없으면 발행하지 않는다").isEmpty();
            restub(app, id, "KT");

            likeUntilPublished(app, liker, room, "KT");
            assertThat(selects(app, id)).isGreaterThanOrEqualTo(2);
        }
    }
}
