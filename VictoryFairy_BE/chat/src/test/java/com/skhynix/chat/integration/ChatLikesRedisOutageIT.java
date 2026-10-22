package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.team.entity.Team;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 장애 시 좋아요 동작. 앱의 Redis 연결을 {@link RedisProxy} 로 돌려, 컨테이너를 건드리지 않고 정지(접속 거부)·무응답
 * (BLACKHOLE)·복구를 만든다. 발행된 메시지와 구독자 수는 프록시를 거치지 않는 {@link LikesProbe} 로 본다.
 *
 * <p>모든 테스트가 새 앱을 띄우므로 서로 영향이 없다.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 240, unit = java.util.concurrent.TimeUnit.SECONDS)
class ChatLikesRedisOutageIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final AtomicLong IDS = new AtomicLong(9_500);
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

    private static AppLauncher launch(RedisProxy proxy, String... args) throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
        AppLauncher app = new AppLauncher();
        app.redisHostOverride = proxy.host();
        app.redisPortOverride = proxy.port();
        return app;
    }

    private static void restub(AppLauncher app, long id, String teamCode) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(teamCode);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        given(app.userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(id)).willReturn(Optional.of(support));
    }

    private record Liker(long id, String token) {
    }

    /** start() 전에 불러야 한다 — 토큰 발급이 목 리포지토리를 채운다. */
    private static List<Liker> likers(AppLauncher app, int count, String teamCode) {
        List<Liker> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = IDS.incrementAndGet();
            String token = app.userToken(id);
            restub(app, id, teamCode);
            list.add(new Liker(id, token));
        }
        return list;
    }

    private static E2eHttp.Resp like(AppLauncher app, Liker liker, String room) {
        return app.http().post("/chat/rooms/" + room + "/likes", liker.token(), null);
    }

    private static List<String> publishedFor(String gameId) {
        return probe().published().stream().filter(m -> m.contains("\"" + gameId + "\"")).toList();
    }

    private static List<E2eHttp.SseEvent> likesOf(E2eHttp.SseStream stream) {
        return stream.dataEvents().stream().filter(e -> "likes".equals(e.name())).toList();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double counter(AppLauncher app, String name, String reason) {
        MeterRegistry meters = app.context.getBean(MeterRegistry.class);
        return reason == null ? meters.get(name).counter().count() : meters.get(name).tag("reason", reason).counter().count();
    }

    private static ListAppender<ILoggingEvent> captureWarns(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        return appender;
    }

    /** ⚠ 앱을 띄운 뒤에 붙여야 한다 — 스프링 부트가 기동하면서 로깅 시스템을 다시 초기화해 먼저 붙인 어펜더를 떼어 낸다. */
    private static void attach(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
    }

    private static void release(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
    }

    /** 구독자 SSE 를 열고 앱의 chat:likes 구독이 서버에 보일 때까지 기다린다. */
    private static E2eHttp.SseStream subscribe(AppLauncher app, String room, String token, long baselineSubscribers) {
        E2eHttp.SseStream stream = app.http().subscribe("/chat/rooms/" + room + "/subscribe", token, null);
        SseEmitterRegistry registry = app.context.getBean(SseEmitterRegistry.class);
        await().atMost(WAIT).until(() -> registry.count(room) == 1);
        await().atMost(WAIT).until(() -> probe().gatewaySubscribers() > baselineSubscribers);
        return stream;
    }

    /** 복구 직후에는 발행 쪽 연결이 아직 붙지 않았을 수 있어, 새 사용자로 하나가 실제로 발행될 때까지 재시도한다. */
    private static long likeUntilPublished(AppLauncher app, List<Liker> pool, String room, Duration limit) {
        long start = System.nanoTime();
        int next = 0;
        while (System.nanoTime() - start < limit.toNanos()) {
            assertThat(like(app, pool.get(next++ % pool.size()), room).status()).isEqualTo(202);
            sleep(1_000);
            if (!publishedFor(room).isEmpty()) {
                return (System.nanoTime() - start) / 1_000_000;
            }
        }
        throw new AssertionError("Redis 복구 뒤 " + limit + " 안에 발행이 재개되지 않았다");
    }

    // ---------- LK-35 / LK-36 ----------

    @Test
    @DisplayName("[CHAT-LK-35][CHAT-LK-36] Redis 가 끊겼다 돌아와도 열린 SSE 는 닫히지 않고(:ping 계속), 파드 재시작 없이 30초 안에 chat:likes 구독이 복구되며 새 좋아요가 likes 로 전달된다. 끊긴 동안의 좋아요는 복구 뒤에도 전달되지 않는다")
    void redisRestart_sseSurvives_resubscribesWithin30s() throws Exception {
        long baseline = probe().gatewaySubscribers();
        ListAppender<ILoggingEvent> warns = captureWarns(Logger.ROOT_LOGGER_NAME);
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            Liker subscriber = likers(app, 1, "OB").get(0);
            List<Liker> likers = likers(app, 12, "HT");
            app.start();
            attach(Logger.ROOT_LOGGER_NAME, warns);
            String room = app.room();

            try (E2eHttp.SseStream stream = subscribe(app, room, subscriber.token(), baseline)) {
                assertThat(like(app, likers.get(0), room).status()).isEqualTo(202);
                await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);

                warns.list.clear();
                proxy.set(RedisProxy.Mode.DOWN);
                await().atMost(WAIT).alias("프록시 차단이 서버에 구독 해제로 보인다").until(() -> probe().gatewaySubscribers() == baseline);
                sleep(8_000);

                assertThat(stream.isEnded()).as("Redis 가 없는 동안에도 SSE 는 유지된다").isFalse();
                String outageRoom = app.room();
                assertThat(like(app, likers.get(1), outageRoom).status()).as("장애 중에도 202").isEqualTo(202);
                sleep(3_000); // 명령 타임아웃(2s)이 지나 PUBLISH 가 실패로 확정된 뒤에 복구한다. 타임아웃 안에 복구되면 대기 중이던 명령은 정상 발행된다.

                proxy.set(RedisProxy.Mode.UP);
                long upAt = System.nanoTime();
                await().atMost(Duration.ofSeconds(45)).until(() -> probe().gatewaySubscribers() == baseline + 1);
                long resubscribedMs = (System.nanoTime() - upAt) / 1_000_000;

                assertThat(resubscribedMs).as("Redis 복구 뒤 구독 복구까지(ms)").isLessThanOrEqualTo(30_000L);
                assertThat(warns.list).as("구독이 끊긴 동안 WARN 이 남는다")
                        .anyMatch(e -> e.getLevel() == Level.WARN);
                likeUntilPublished(app, likers.subList(2, likers.size()), room, WAIT);
                await().atMost(WAIT).until(() -> likesOf(stream).size() >= 2);
                assertThat(publishedFor(outageRoom)).as("장애 중 좋아요는 복구 뒤에도 발행되지 않는다").isEmpty();
                assertThat(stream.isEnded()).isFalse();
                await().atMost(Duration.ofSeconds(20)).alias(":ping 하트비트 유지").until(() ->
                        stream.events().stream().anyMatch(e -> "ping".equals(e.comment())));
            }
        } finally {
            release(Logger.ROOT_LOGGER_NAME, warns);
        }
    }

    // ---------- LK-50 ----------

    @Test
    @DisplayName("[CHAT-LK-50][CHAT-LK-22][CHAT-LK-23] 기동 때부터 Redis 가 죽어 있어도 앱은 기동에 성공하고, 좋아요는 지연 없이 202 이며 publish.failed 가 늘고, Redis 가 나타나면 30초 안에 구독하고 발행이 재개된다")
    void redisDeadAtBoot_appStarts_fast202_recoversWhenRedisAppears() throws Exception {
        long baseline = probe().gatewaySubscribers();
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            List<Liker> likers = likers(app, 30, "HT");
            Liker subscriber = likers(app, 1, "OB").get(0);

            app.start();

            String room = app.room();
            String outageRoom = app.room();
            long worstMs = 0;
            for (Liker liker : likers.subList(0, 10)) {
                long t = System.nanoTime();
                assertThat(like(app, liker, outageRoom).status()).isEqualTo(202);
                worstMs = Math.max(worstMs, (System.nanoTime() - t) / 1_000_000);
            }
            assertThat(worstMs).as("Redis 타임아웃(2s)만큼 늘지 않는다").isLessThan(1_000);
            await().atMost(WAIT).until(() -> counter(app, "chat.likes.publish.failed", null) >= 10);
            assertThat(app.http().get("/chat/rooms", subscriber.token()).status()).as("Redis 를 안 보는 목록은 정상").isEqualTo(200);

            proxy.set(RedisProxy.Mode.UP);
            long upAt = System.nanoTime();
            await().atMost(Duration.ofSeconds(60)).until(() -> probe().gatewaySubscribers() == baseline + 1);
            long resubscribedMs = (System.nanoTime() - upAt) / 1_000_000;
            assertThat(resubscribedMs).as("Redis 가 나타난 뒤 구독까지(ms)").isLessThanOrEqualTo(30_000L);

            try (E2eHttp.SseStream stream = subscribe(app, room, subscriber.token(), baseline)) {
                likeUntilPublished(app, likers.subList(10, likers.size()), room, WAIT);
                await().atMost(WAIT).until(() -> !likesOf(stream).isEmpty());
            }
            assertThat(publishedFor(outageRoom)).as("장애 중 좋아요는 재시도·재발행되지 않는다").isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-LK-50] Redis 가 응답하지 않아도(무응답) 202 응답 시간이 명령 타임아웃(2초)만큼 늘지 않고, 연타 중에도 Redis 를 안 보는 다른 요청이 느려지지 않는다")
    void redisBlackhole_202StaysFast_otherRequestsUnaffected() throws Exception {
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            List<Liker> likers = likers(app, 60, "HT");
            Liker other = likers(app, 1, "OB").get(0);
            app.start();
            String warm = app.room();
            likeUntilPublished(app, likers.subList(0, 5), warm, WAIT);

            proxy.set(RedisProxy.Mode.BLACKHOLE);
            String room = app.room();
            long worstLikeMs = 0;
            long worstOtherMs = 0;
            long loopStart = System.nanoTime();
            for (int i = 5; i < 55; i++) {
                long t = System.nanoTime();
                assertThat(like(app, likers.get(i), room).status()).isEqualTo(202);
                worstLikeMs = Math.max(worstLikeMs, (System.nanoTime() - t) / 1_000_000);
                long t2 = System.nanoTime();
                assertThat(app.http().get("/chat/rooms", other.token()).status()).isEqualTo(200);
                worstOtherMs = Math.max(worstOtherMs, (System.nanoTime() - t2) / 1_000_000);
            }
            long totalMs = (System.nanoTime() - loopStart) / 1_000_000;

            assertThat(worstLikeMs).as("무응답 Redis 에서 202 최악 응답(ms)").isLessThan(1_000);
            assertThat(worstOtherMs).as("연타 중 목록 조회 최악 응답(ms)").isLessThan(1_000);
            assertThat(totalMs).as("50쌍 요청이 Redis 타임아웃에 묶이지 않았다").isLessThan(10_000);
            await().atMost(Duration.ofSeconds(15)).until(() -> counter(app, "chat.likes.publish.failed", null) >= 1);
        }
    }

    // ---------- LK-51 / LK-52 ----------

    @Test
    @DisplayName("[CHAT-LK-51][CHAT-LK-52][CHAT-LK-23] 대기열 상한 5 + Redis 무응답에서 사용자 20명이 1번씩 누르면 20회 전부 202, queue-full 15건, 그 WARN 은 1줄(직전 로그 이후 건수 포함). 타임아웃 뒤 미완료 수가 새지 않아 Redis 복구 후 20명이 다시 전부 발행되고, 버린 좋아요는 재발행되지 않는다")
    void queueFull_dropsOverflow_noLeak_recoversAfterRedisReturns() throws Exception {
        String loggerName = "com.skhynix.chat.like.service.LikePublisher";
        ListAppender<ILoggingEvent> warns = captureWarns(loggerName);
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            List<Liker> likers = likers(app, 80, "HT");
            app.start("--chat.likes.publish-queue-capacity=5");
            attach(loggerName, warns);
            likeUntilPublished(app, likers.subList(0, 5), app.room(), WAIT);
            String room = app.room();
            warns.list.clear();
            double queueFullBefore = counter(app, "chat.likes.dropped", "queue-full");

            proxy.set(RedisProxy.Mode.BLACKHOLE);
            long start = System.nanoTime();
            for (int i = 5; i < 25; i++) {
                assertThat(like(app, likers.get(i), room).status()).as("user " + i).isEqualTo(202);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            double dropped = counter(app, "chat.likes.dropped", "queue-full") - queueFullBefore;
            assertThat(elapsedMs).as("20회가 Redis 타임아웃 안에 끝나야 이 단언이 성립").isLessThan(1_800);
            assertThat(dropped).as("queue-full").isGreaterThanOrEqualTo(15.0);
            List<ILoggingEvent> queueWarns = warns.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("대기열")).toList();
            assertThat(queueWarns).as("WARN 줄 수(10초에 1줄)").hasSize(1);
            assertThat(queueWarns.get(0).getFormattedMessage()).contains("직전 로그 이후");

            await().atMost(Duration.ofSeconds(15)).until(() -> counter(app, "chat.likes.publish.failed", null) >= 5);
            proxy.set(RedisProxy.Mode.UP);
            sleep(2_500);
            assertThat(publishedFor(room)).as("버려진·타임아웃된 좋아요는 복구 뒤에도 발행되지 않는다").isEmpty();

            String after = app.room();
            likeUntilPublished(app, likers.subList(25, 40), after, Duration.ofSeconds(45));
            double queueFullAtRecovery = counter(app, "chat.likes.dropped", "queue-full");
            int already = publishedFor(after).size();
            for (int i = 40; i < 60; i++) {
                assertThat(like(app, likers.get(i), after).status()).isEqualTo(202);
            }
            await().atMost(WAIT).alias("미완료 수가 새지 않아 20건이 전부 발행된다")
                    .until(() -> publishedFor(after).size() >= already + 20);
            assertThat(counter(app, "chat.likes.dropped", "queue-full")).as("복구 뒤 queue-full 증가 없음")
                    .isEqualTo(queueFullAtRecovery);
        } finally {
            release(loggerName, warns);
        }
    }

    // ---------- LK-22 / LK-23 ----------

    @Test
    @DisplayName("[CHAT-LK-22][CHAT-LK-23] 연결된 뒤 Redis 가 정지하면 좋아요는 202 이고 publish.failed 메트릭(actuator 포함)이 오르며, 그 좋아요들은 Redis 복구 뒤에도 다시 발행되지 않는다")
    void redisStopped_failedCounted_neverRepublishedAfterRecovery() throws Exception {
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            List<Liker> likers = likers(app, 40, "HT");
            app.start();
            likeUntilPublished(app, likers.subList(0, 5), app.room(), WAIT);
            String outageRoom = app.room();
            double failedBefore = counter(app, "chat.likes.publish.failed", null);

            proxy.set(RedisProxy.Mode.DOWN);
            sleep(500);
            for (int i = 5; i < 8; i++) {
                assertThat(like(app, likers.get(i), outageRoom).status()).isEqualTo(202);
            }

            await().atMost(Duration.ofSeconds(15)).until(() -> counter(app, "chat.likes.publish.failed", null) - failedBefore >= 3);
            E2eHttp.Resp metric = app.http().get("/chat/actuator/metrics/chat.likes.publish.failed", likers.get(0).token());
            assertThat(metric.status()).isEqualTo(200);
            assertThat(metric.json().get("measurements").get(0).get("value").asDouble())
                    .isEqualTo(counter(app, "chat.likes.publish.failed", null));

            proxy.set(RedisProxy.Mode.UP);
            likeUntilPublished(app, likers.subList(10, 30), app.room(), Duration.ofSeconds(45));
            sleep(2_000);
            assertThat(publishedFor(outageRoom)).as("장애 중 좋아요는 복구 뒤에도 재발행되지 않는다").isEmpty();
        }
    }

    @Test
    @DisplayName("[CHAT-LK-22] Redis 가 오래(8초) 정지한 뒤에 보낸 좋아요도 재시도 없이 버려져, 복구 후에도 다시 발행되지 않는다")
    void longOutage_likeSentLateInOutage_neverRepublished() throws Exception {
        try (RedisProxy proxy = new RedisProxy(Containers.redisHost(), Containers.redisPort());
                AppLauncher app = launch(proxy)) {
            proxy.set(RedisProxy.Mode.UP);
            List<Liker> likers = likers(app, 40, "HT");
            app.start();
            likeUntilPublished(app, likers.subList(0, 5), app.room(), WAIT);
            String lateRoom = app.room();

            proxy.set(RedisProxy.Mode.DOWN);
            sleep(8_000);
            assertThat(like(app, likers.get(6), lateRoom).status()).isEqualTo(202);
            sleep(3_000);

            proxy.set(RedisProxy.Mode.UP);
            likeUntilPublished(app, likers.subList(10, 30), app.room(), Duration.ofSeconds(45));
            sleep(3_000);
            assertThat(publishedFor(lateRoom)).as("긴 정지 중에 보낸 좋아요는 복구 뒤에도 재발행되지 않는다").isEmpty();
        }
    }
}
