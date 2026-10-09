package com.skhynix.chat.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방별 leading-edge 스로틀. 쓰기 풀은 목으로 대체해 "누구에게 어떤 프레임을 언제 맡겼는가"만 본다.
 * 시간 의존 검증은 창을 길게(500~1000ms) 잡아 타이머 오차와 구별되게 한다.
 */
class LikesThrottlerTest {

    private static final String ROOM = "G1";

    private record Delivery(ChatSubscription to, SseFrame frame, long atNanos, String thread) {

        @SuppressWarnings("unchecked")
        List<String> codes() {
            return (List<String>) frame.data();
        }
    }

    private final List<Delivery> deliveries = Collections.synchronizedList(new ArrayList<>());
    private SseFrameWriter writer;
    private SseEmitterRegistry registry;
    private LikesThrottler throttler;

    private void init(long windowMs) {
        writer = mock(SseFrameWriter.class);
        doAnswer(invocation -> {
            deliveries.add(new Delivery(invocation.getArgument(0), invocation.getArgument(1), System.nanoTime(),
                    Thread.currentThread().getName()));
            return null;
        }).when(writer).deliver(any(), any());
        registry = new SseEmitterRegistry(writer);
        throttler = new LikesThrottler(registry, writer,
                new ChatLikesProperties(new ChatLikesProperties.RateLimit(10), 300, windowMs, 10_000));
        throttler.start();
    }

    @AfterEach
    void tearDown() {
        if (throttler != null) {
            throttler.stop();
        }
    }

    private List<Delivery> to(ChatSubscription subscription) {
        synchronized (deliveries) {
            return deliveries.stream().filter(d -> d.to() == subscription).toList();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- LK-42 ----------

    @Test
    @DisplayName("[CHAT-LK-42] 조용한 방의 첫 좋아요는 창(1초)을 기다리지 않고 그 구단 코드 하나를 구독자 전원에게 즉시 보낸다")
    void quietRoom_firstLikeDeliveredImmediatelyToAllSubscribers() {
        init(1_000);
        ChatSubscription a = registry.register(ROOM, 1L, false);
        ChatSubscription b = registry.register(ROOM, 2L, false);

        long offeredAt = System.nanoTime();
        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofMillis(400)).until(() -> to(a).size() == 1 && to(b).size() == 1);
        assertThat(to(a).get(0).atNanos() - offeredAt).as("서버 측 대기").isLessThan(400_000_000L);
        for (ChatSubscription s : List.of(a, b)) {
            Delivery d = to(s).get(0);
            assertThat(d.frame().name()).isEqualTo("likes");
            assertThat(d.frame().id()).as("likes 는 id 가 없다").isNull();
            assertThat(d.codes()).containsExactly("HT");
        }
    }

    @Test
    @DisplayName("[CHAT-LK-42] 다른 방 구독자에게는 전달되지 않는다")
    void deliveredOnlyToThatRoom() {
        init(200);
        ChatSubscription inRoom = registry.register(ROOM, 1L, false);
        ChatSubscription elsewhere = registry.register("OTHER", 2L, false);

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(2)).until(() -> to(inRoom).size() == 1);
        sleep(300);
        assertThat(to(elsewhere)).isEmpty();
    }

    // ---------- LK-43/44/45 ----------

    @Test
    @DisplayName("[CHAT-LK-43][CHAT-LK-44] 즉시 전송 뒤 창(500ms) 안에 온 HT·HT·LG·OB·HT 는 창이 끝나기 전에는 프레임이 없다")
    void duringWindow_noFrameBeforeWindowEnds() {
        init(500);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);

        sleep(50);
        for (String code : List.of("HT", "HT", "LG", "OB", "HT")) {
            throttler.offer(ROOM, code);
        }
        sleep(250);

        assertThat(to(s)).as("창이 끝나기 전(약 300ms 시점)").hasSize(1);
    }

    @Test
    @DisplayName("[CHAT-LK-45][CHAT-LK-27] 창이 끝나면 모음 {HT,LG,OB} 가 중복 없는 배열 1프레임으로 나간다")
    void windowEnd_oneFrameWithDedupedSet() {
        init(400);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);

        for (String code : List.of("HT", "HT", "LG", "OB", "HT")) {
            throttler.offer(ROOM, code);
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> to(s).size() == 2);
        sleep(300);
        assertThat(to(s)).hasSize(2);
        assertThat(to(s).get(1).codes()).containsExactlyInAnyOrder("HT", "LG", "OB");
        assertThat(to(s).get(1).frame().id()).isNull();
    }

    @Test
    @DisplayName("[CHAT-LK-27] 한 창 안에 같은 구단 좋아요가 500건 들어와도 창 끝 프레임은 [\"HT\"] 하나다 (개수를 싣지 않는다)")
    void fiveHundredSameTeam_singleCodeNoCount() {
        init(400);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);

        for (int i = 0; i < 500; i++) {
            throttler.offer(ROOM, "HT");
        }

        await().atMost(Duration.ofSeconds(2)).until(() -> to(s).size() == 2);
        assertThat(to(s).get(1).codes()).containsExactly("HT");
    }

    // ---------- LK-46 ----------

    @Test
    @DisplayName("[CHAT-LK-46] 3초 동안 끊임없이 좋아요가 들어오면 구독자당 프레임은 (즉시 1 + 창 끝 최대 30) = 31개 이하이고, 프레임 간격은 창(100ms)보다 짧아지지 않는다")
    void sustainedLikes_atMost31FramesAnd100msSpacing() {
        init(100);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        String[] teams = {"HT", "LG", "OB"};

        long start = System.nanoTime();
        int i = 0;
        while (System.nanoTime() - start < 3_000_000_000L) {
            throttler.offer(ROOM, teams[i++ % teams.length]);
            sleep(5);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        sleep(250);

        List<Delivery> got = to(s);
        assertThat(got.size()).as("프레임 수").isLessThanOrEqualTo(31 + 3);
        assertThat(got.size()).as("최소한 창 단위로는 계속 나간다").isGreaterThanOrEqualTo(20);
        assertThat(got.size()).as("이론 상한 1 + (경과+250)/100").isLessThanOrEqualTo((int) (1 + (elapsedMs + 250) / 100));
        for (int k = 1; k < got.size(); k++) {
            long gapMs = (got.get(k).atNanos() - got.get(k - 1).atNanos()) / 1_000_000;
            assertThat(gapMs).as("프레임 %d-%d 간격", k - 1, k).isGreaterThanOrEqualTo(90);
        }
        for (Delivery d : got) {
            assertThat(d.codes()).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(3);
        }
    }

    // ---------- LK-47 ----------

    @Test
    @DisplayName("[CHAT-LK-47] 좋아요 1회 뒤 아무것도 오지 않으면 즉시 프레임 1개 뒤에는 어떤 프레임도 없다 (빈 배열 프레임 없음)")
    void emptyWindow_noFurtherFrames() {
        init(150);
        ChatSubscription s = registry.register(ROOM, 1L, false);

        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        sleep(700);

        assertThat(to(s)).hasSize(1);
        assertThat(to(s).get(0).codes()).containsExactly("HT");
    }

    @Test
    @DisplayName("[CHAT-LK-47] 빈 창이 끝나 조용해진 방의 다음 좋아요는 창을 기다리지 않고 다시 즉시 나간다")
    void afterQuietReturn_nextLikeIsImmediateAgain() {
        init(400);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        sleep(700);

        long offeredAt = System.nanoTime();
        throttler.offer(ROOM, "LG");

        await().atMost(Duration.ofMillis(300)).until(() -> to(s).size() == 2);
        assertThat(to(s).get(1).atNanos() - offeredAt).as("창(400ms)을 기다리지 않는다").isLessThan(300_000_000L);
        assertThat(to(s).get(1).codes()).containsExactly("LG");
    }

    @Test
    @DisplayName("[CHAT-LK-46] 창 끝에 전송했으면 새 창이 열려, 그 직후 온 좋아요는 즉시 나가지 않고 다음 창 끝에 나간다")
    void afterTrailingSend_newWindowOpens() {
        init(400);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        throttler.offer(ROOM, "LG");
        await().atMost(Duration.ofSeconds(2)).until(() -> to(s).size() == 2);

        long sentAt = to(s).get(1).atNanos();
        throttler.offer(ROOM, "OB");
        sleep(150);

        assertThat(to(s)).as("새 창이 열려 있어 아직 안 나갔다").hasSize(2);
        await().atMost(Duration.ofSeconds(2)).until(() -> to(s).size() == 3);
        assertThat((to(s).get(2).atNanos() - sentAt) / 1_000_000).isGreaterThanOrEqualTo(350);
        assertThat(to(s).get(2).codes()).containsExactly("OB");
    }

    @Test
    @DisplayName("[CHAT-LK-42] 방마다 창이 따로다 — A방 창이 열려 있어도 B방의 첫 좋아요는 즉시 나간다")
    void windowsAreIndependentPerRoom() {
        init(1_000);
        ChatSubscription inA = registry.register("A", 1L, false);
        ChatSubscription inB = registry.register("B", 2L, false);
        throttler.offer("A", "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(inA).size() == 1);

        throttler.offer("B", "LG");

        await().atMost(Duration.ofMillis(400)).until(() -> to(inB).size() == 1);
    }

    // ---------- LK-30 / LK-31 ----------

    @Test
    @DisplayName("[CHAT-LK-30] 발신자를 빼지 않는다 — 방에 구독자 A 한 명뿐일 때 좋아요가 와도 A 가 받는다")
    void senderIsNotExcluded_soleSubscriberReceives() {
        init(200);
        ChatSubscription onlyA = registry.register(ROOM, 1L, false);

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(1)).until(() -> to(onlyA).size() == 1);
    }

    @Test
    @DisplayName("[CHAT-LK-31] 차단 필터가 없다 — 같은 방 구독자 모두가 같은 프레임을 받는다")
    void noBlockFilter_everyoneGetsSameFrame() {
        init(200);
        List<ChatSubscription> subs = new ArrayList<>();
        for (long uid = 1; uid <= 5; uid++) {
            subs.add(registry.register(ROOM, uid, false));
        }

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(1)).until(() -> subs.stream().allMatch(s -> to(s).size() == 1));
        assertThat(subs.stream().map(s -> to(s).get(0).codes()).collect(Collectors.toSet()))
                .containsExactly(List.of("HT"));
    }

    // ---------- LK-32 ----------

    @Test
    @DisplayName("[CHAT-LK-32] 구독자가 없는 방의 좋아요는 버리고 창도 열지 않는다 — 그 뒤 구독한 사용자는 그 좋아요를 못 받고, 다음 좋아요는 즉시 받는다")
    void noSubscribers_dropped_noWindowOpened() {
        init(1_000);
        throttler.offer(ROOM, "HT");
        sleep(200);
        ChatSubscription late = registry.register(ROOM, 1L, false);
        sleep(200);
        assertThat(to(late)).as("구독 전의 좋아요는 받지 않는다").isEmpty();

        long offeredAt = System.nanoTime();
        throttler.offer(ROOM, "LG");

        await().atMost(Duration.ofMillis(400)).until(() -> to(late).size() == 1);
        assertThat(to(late).get(0).codes()).containsExactly("LG");
        assertThat(to(late).get(0).atNanos() - offeredAt).as("창이 열려 있었다면 1초를 기다렸을 것").isLessThan(400_000_000L);
    }

    @Test
    @DisplayName("[CHAT-LK-32] 존재하지 않는 gameId 의 좋아요가 와도 예외 없이 버려지고 아무것도 전달되지 않는다")
    void unknownGameId_silentlyDropped() {
        init(100);
        ChatSubscription other = registry.register(ROOM, 1L, false);

        throttler.offer("NOPE-NOPE", "HT");
        sleep(300);

        assertThat(deliveries).isEmpty();
        assertThat(to(other)).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-LK-32] 창이 열린 사이 구독자가 모두 나가면 창 끝 모음은 버려지고, 그 방은 조용한 상태로 돌아가 새 구독자의 첫 좋아요가 즉시 나간다")
    void subscribersLeaveDuringWindow_collectionDropped_roomQuietAgain() {
        init(300);
        ChatSubscription first = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(first).size() == 1);
        throttler.offer(ROOM, "LG");
        registry.closeSubscriptions(ROOM, 1L);
        sleep(500);
        assertThat(to(first)).hasSize(1);

        ChatSubscription second = registry.register(ROOM, 2L, false);
        throttler.offer(ROOM, "OB");

        await().atMost(Duration.ofMillis(250)).until(() -> to(second).size() == 1);
        assertThat(to(second).get(0).codes()).containsExactly("OB");
    }

    // ---------- 갇힘 방지 ----------

    @Test
    @DisplayName("[CHAT-LK-43] stop 뒤 start 하면 창이 열려 있던 방도 갇히지 않고 다음 좋아요가 즉시 나간다")
    void stopThenStart_roomNotStuckInOpenWindow() {
        init(5_000);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        throttler.offer(ROOM, "LG");
        sleep(100);

        throttler.stop();
        throttler.start();
        throttler.offer(ROOM, "OB");

        await().atMost(Duration.ofMillis(800)).until(() -> to(s).size() == 2);
        assertThat(to(s).get(1).codes()).as("멈추기 전에 모으던 LG 는 버려지고 새 좋아요만").containsExactly("OB");
        assertThat(throttler.isRunning()).isTrue();
    }

    @Test
    @DisplayName("[CHAT-LK-43] 전송(deliver)이 예외를 던져도 창 끝 예약이 남아 방이 '창 열림'에 갇히지 않는다")
    void deliverThrows_roomNotStuck() {
        init(200);
        AtomicInteger calls = new AtomicInteger();
        ChatSubscription s = registry.register(ROOM, 1L, false);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("writer boom");
            }
            deliveries.add(new Delivery(invocation.getArgument(0), invocation.getArgument(1), System.nanoTime(),
                    Thread.currentThread().getName()));
            return null;
        }).when(writer).deliver(any(), any());

        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() == 1);
        sleep(500);
        throttler.offer(ROOM, "LG");

        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        assertThat(to(s).get(0).codes()).containsExactly("LG");
    }

    // ---------- LK-34 ----------

    @Test
    @DisplayName("[CHAT-LK-34] 프레임 전달은 offer 를 부른 스레드가 아니라 전용 스로틀 스레드에서 쓰기 풀(deliver)로만 맡긴다 — emitter 에 직접 쓰지 않는다")
    void deliveryRunsOnThrottlerThreadViaWriterOnly() {
        init(100);
        ChatSubscription s = registry.register(ROOM, 1L, false);

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(1)).until(() -> to(s).size() == 1);
        assertThat(to(s).get(0).thread()).isEqualTo("chat-likes-throttler").isNotEqualTo(Thread.currentThread().getName());
        verify(writer, never()).enqueue(any(), any());
        verify(writer, never()).open(any());
    }

    @Test
    @DisplayName("[CHAT-LK-43] 멈춘 뒤(stop)에는 offer 가 예외 없이 무시된다")
    void offerAfterStop_ignored() {
        init(100);
        ChatSubscription s = registry.register(ROOM, 1L, false);
        throttler.stop();

        throttler.offer(ROOM, "HT");
        sleep(200);

        assertThat(to(s)).isEmpty();
        assertThat(throttler.isRunning()).isFalse();
    }

    @Test
    @DisplayName("[CHAT-LK-24] 라이프사이클 단계는 게이트웨이 배처와 같고 Redis 구독보다 늦게 멈춘다")
    void phaseOrdering() {
        assertThat(LikesThrottler.PHASE).isEqualTo(RoomBatcher.PHASE);
        assertThat(LikesSubscriber.PHASE).isGreaterThan(LikesThrottler.PHASE);
    }
}
