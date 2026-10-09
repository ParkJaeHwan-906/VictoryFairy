package com.skhynix.chat.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skhynix.chat.gateway.service.LikesSubscriber;
import com.skhynix.chat.gateway.service.LikesThrottler;
import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.like.controller.ChatLikeController;
import com.skhynix.chat.like.service.ChatLikeService;
import com.skhynix.chat.like.service.LikeMetrics;
import com.skhynix.chat.like.service.LikePublisher;
import com.skhynix.chat.like.service.LikeRateLimiter;
import com.skhynix.chat.like.service.SupportTeamCodeCache;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.team.entity.Team;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 역할 플래그 조합·설정 키 덮어쓰기·파드 간 전달. 스프링 테스트 캐시 밖에서 앱을 직접 띄우고 닫는다(AppLauncher).
 * 환경변수 바인딩(CHAT_LIKES_*)은 프로세스 환경을 못 바꾸므로 같은 relaxed binding 인 {@code --chat.likes.*} 인자로 대신한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChatLikesPodsIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final AtomicLong IDS = new AtomicLong(9_100);
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

    private static void prepareBroker() throws Exception {
        Containers.createChatTopics(2);
        Containers.startHistoryWriterGroupFromEnd();
    }

    /** 응원 구단이 있는 사용자 토큰. start() 전에 불러도 된다(목 리포지토리). */
    private static String token(AppLauncher app, long id, String teamCode) {
        String token = app.userToken(id);
        restub(app, id, teamCode);
        return token;
    }

    private static void restub(AppLauncher app, long id, String teamCode) {
        Team team = mock(Team.class);
        when(team.getCode()).thenReturn(teamCode);
        UserSupportTeam support = mock(UserSupportTeam.class);
        when(support.getTeam()).thenReturn(team);
        given(app.userSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull(id)).willReturn(Optional.of(support));
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

    private static E2eHttp.SseStream subscribe(AppLauncher app, String room, String token, long baselineSubscribers) {
        E2eHttp.SseStream stream = app.http().subscribe("/chat/rooms/" + room + "/subscribe", token, null);
        SseEmitterRegistry registry = app.context.getBean(SseEmitterRegistry.class);
        await().atMost(WAIT).until(() -> registry.count(room) == 1);
        await().atMost(WAIT).until(() -> probe().gatewaySubscribers() > baselineSubscribers);
        return stream;
    }

    // ---------- LK-38 / LK-39 / LK-24 ----------

    @Test
    @DisplayName("[CHAT-LK-38] API 역할을 끈 파드는 좋아요 엔드포인트가 없어 인증된 POST 도 404 이고, 컨트롤러·서비스·캐시·속도 제한·발행기·메트릭 빈이 없으며 PUBLISH 가 일어나지 않는다")
    void apiRoleOff_noLikeEndpointNoPublishBeans() throws Exception {
        prepareBroker();
        try (AppLauncher app = new AppLauncher()) {
            String token = token(app, IDS.incrementAndGet(), "HT");
            app.start("--chat.role.api=false", "--chat.role.history-writer=false");
            String room = "APIOFF" + System.nanoTime() % 1_000_000;
            Map<String, Long> before = probe().commandCalls();

            E2eHttp.Resp resp = app.http().post("/chat/rooms/" + room + "/likes", token, null);

            assertThat(resp.status()).isEqualTo(404);
            sleep(500);
            assertThat(probe().callsSince(before)).doesNotContainKey("publish");
            assertThat(publishedFor(room)).isEmpty();
            for (Class<?> type : List.of(ChatLikeController.class, ChatLikeService.class, SupportTeamCodeCache.class,
                    LikeRateLimiter.class, LikePublisher.class, LikeMetrics.class)) {
                assertThat(app.context.getBeansOfType(type)).as(type.getSimpleName()).isEmpty();
            }
            assertThat(app.http().post("/chat/rooms/" + room + "/likes", null, null).status()).as("미인증은 401").isEqualTo(401);
        }
    }

    @Test
    @DisplayName("[CHAT-LK-39] 게이트웨이 역할을 끈 파드(history-writer 전용)는 chat:likes 를 구독하지 않고 수신 빈이 없다. 기본 역할 파드는 구독자를 정확히 1 늘린다(PUBSUB NUMSUB)")
    void gatewayRoleOff_noSubscription_defaultPodAddsOne() throws Exception {
        prepareBroker();
        long baseline = probe().gatewaySubscribers();
        try (AppLauncher writerOnly = new AppLauncher()) {
            writerOnly.start("--chat.role.api=false", "--chat.role.gateway=false");

            sleep(3_000);
            assertThat(probe().gatewaySubscribers()).as("history-writer 전용 파드는 구독하지 않는다").isEqualTo(baseline);
            for (Class<?> type : List.of(LikesSubscriber.class, LikesThrottler.class)) {
                assertThat(writerOnly.context.getBeansOfType(type)).as(type.getSimpleName()).isEmpty();
            }
        }
        try (AppLauncher full = new AppLauncher()) {
            full.start();

            await().atMost(WAIT).until(() -> probe().gatewaySubscribers() == baseline + 1);
            assertThat(full.context.getBeansOfType(LikesSubscriber.class)).hasSize(1);
        }
        await().atMost(WAIT).until(() -> probe().gatewaySubscribers() == baseline);
    }

    @Test
    @DisplayName("[CHAT-LK-24] 파드 2개: A 파드로 좋아요를 보내면 B 파드에 붙은 그 방 구독자가 likes 를 받는다. NUMSUB 은 게이트웨이 파드 수만큼 늘어난다")
    void twoPods_likeOnAReachesSubscriberOnB() throws Exception {
        prepareBroker();
        long baseline = probe().gatewaySubscribers();
        try (AppLauncher podA = new AppLauncher(); AppLauncher podB = new AppLauncher()) {
            String liker = token(podA, IDS.incrementAndGet(), "HT");
            String subscriber = token(podB, IDS.incrementAndGet(), "OB");
            podA.start();
            podB.start();
            await().atMost(WAIT).until(() -> probe().gatewaySubscribers() == baseline + 2);
            String room = podB.room();

            try (E2eHttp.SseStream stream = subscribe(podB, room, subscriber, baseline + 1)) {
                assertThat(podA.http().post("/chat/rooms/" + room + "/likes", liker, null).status()).isEqualTo(202);

                await().atMost(WAIT).until(() -> likesOf(stream).size() == 1);
                assertThat(likesOf(stream).get(0).data()).isEqualTo("[\"HT\"]");
                assertThat(likesOf(stream).get(0).id()).isNull();
            }
        }
    }

    // ---------- LK-40 ----------

    @Test
    @DisplayName("[CHAT-LK-40] 설정 없이 기동하면 키 4개가 표의 기본값(10 / 300 / 100 / 10000)이다")
    void defaults() throws Exception {
        prepareBroker();
        try (AppLauncher app = new AppLauncher()) {
            app.start();

            ChatLikesProperties properties = app.context.getBean(ChatLikesProperties.class);
            assertThat(properties.rateLimit().perSecond()).isEqualTo(10);
            assertThat(properties.teamCacheTtlSeconds()).isEqualTo(300L);
            assertThat(properties.throttleWindowMs()).isEqualTo(100L);
            assertThat(properties.publishQueueCapacity()).isEqualTo(10_000);
        }
    }

    @Test
    @DisplayName("[CHAT-LK-40][CHAT-LK-16] chat.likes.rate-limit.per-second=3 으로 기동하면 같은 초 4번째부터 버려지고(여전히 202) 발행은 3건이다")
    void rateLimitOverriddenToThree() throws Exception {
        prepareBroker();
        try (AppLauncher app = new AppLauncher()) {
            String token = token(app, IDS.incrementAndGet(), "HT");
            app.start("--chat.likes.rate-limit.per-second=3");
            String room = "RATE3" + System.nanoTime() % 1_000_000;
            MeterRegistry meters = app.context.getBean(MeterRegistry.class);

            long start = System.nanoTime();
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                statuses.add(app.http().post("/chat/rooms/" + room + "/likes", token, null).status());
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            await().atMost(WAIT).until(() -> publishedFor(room).size() >= 3);
            sleep(400);

            assertThat(statuses).containsOnly(202);
            if (elapsedMs < 950) {
                assertThat(publishedFor(room)).hasSize(3);
                assertThat(meters.get("chat.likes.dropped").tag("reason", "rate-limit").counter().count()).isEqualTo(3.0);
            }
        }
    }

    @Test
    @DisplayName("[CHAT-LK-12][CHAT-LK-40] team-cache-ttl-seconds=2 로 기동하면 구단 변경이 2초 안에는 옛 값(HT), 2초 뒤에는 새 값(LG)으로 발행된다")
    void teamCacheTtlBoundary() throws Exception {
        prepareBroker();
        try (AppLauncher app = new AppLauncher()) {
            long id = IDS.incrementAndGet();
            String token = token(app, id, "HT");
            app.start("--chat.likes.team-cache-ttl-seconds=2");
            String room = "TTL2" + System.nanoTime() % 1_000_000;
            String path = "/chat/rooms/" + room + "/likes";

            long firstAt = System.nanoTime();
            assertThat(app.http().post(path, token, null).status()).isEqualTo(202);
            await().atMost(WAIT).until(() -> publishedFor(room).size() == 1);
            restub(app, id, "LG");

            assertThat(app.http().post(path, token, null).status()).isEqualTo(202);
            await().atMost(WAIT).until(() -> publishedFor(room).size() == 2);
            long elapsedMs = (System.nanoTime() - firstAt) / 1_000_000;
            assertThat(elapsedMs).as("테스트 진행이 TTL 보다 빨랐어야 이 단언이 성립").isLessThan(1_800);
            assertThat(publishedFor(room).get(1)).as("TTL 안: 옛 값").contains("\"teamCode\":\"HT\"");

            long waitMs = 2_300 - (System.nanoTime() - firstAt) / 1_000_000;
            sleep(Math.max(0, waitMs));
            assertThat(app.http().post(path, token, null).status()).isEqualTo(202);
            await().atMost(WAIT).until(() -> publishedFor(room).size() == 3);
            assertThat(publishedFor(room).get(2)).as("TTL 뒤: 새 값").contains("\"teamCode\":\"LG\"");
        }
    }

    @Test
    @DisplayName("[CHAT-LK-40][CHAT-LK-46] throttle-window-ms=500 으로 기동하면 연타 방의 likes 간격이 약 500ms 이고 3초 동안 프레임은 이론 상한(1 + 경과/500) 이하다")
    void throttleWindowOverriddenTo500() throws Exception {
        prepareBroker();
        long baseline = probe().gatewaySubscribers();
        try (AppLauncher app = new AppLauncher()) {
            String subscriber = token(app, IDS.incrementAndGet(), "OB");
            List<String> likers = List.of(token(app, IDS.incrementAndGet(), "HT"), token(app, IDS.incrementAndGet(), "LG"));
            app.start("--chat.likes.throttle-window-ms=500");
            String room = app.room();

            try (E2eHttp.SseStream stream = subscribe(app, room, subscriber, baseline)) {
                ExecutorService pool = Executors.newFixedThreadPool(likers.size());
                long start = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>();
                for (String liker : likers) {
                    futures.add(pool.submit(() -> {
                        while (System.nanoTime() - start < 3_000_000_000L) {
                            app.http().post("/chat/rooms/" + room + "/likes", liker, null);
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
                sleep(700);

                List<E2eHttp.SseEvent> frames = likesOf(stream);
                assertThat(frames.size()).isLessThanOrEqualTo((int) (1 + (elapsedMs + 700) / 500));
                assertThat(frames.size()).as("창 단위로 계속 나간다").isGreaterThanOrEqualTo(4);
            }
        }
    }
}
