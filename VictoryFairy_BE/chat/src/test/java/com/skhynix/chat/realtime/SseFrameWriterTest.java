package com.skhynix.chat.realtime;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.support.SseCapture;
import com.skhynix.chat.support.SseCapture.Event;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 쓰기 전용 풀·복구 중 실시간 프레임 보류·쓰기 타임아웃 감시.
 *
 * <p>"막힌 emitter"는 emitter 의 writeLock 을 다른 스레드가 쥐고 있는 상태로 흉내 낸다. 실제 막힌 소켓 쓰기도
 * 같은 잠금 위에서 {@code send()}/{@code complete()} 를 함께 멈춘다(ChatSseEmitter 주석).
 */
class SseFrameWriterTest {

    private static final String ROOM = "G1";

    private SseFrameWriter writer;
    private SseEmitterRegistry registry;
    private final List<Thread> blockers = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        writer = new SseFrameWriter(props(150, 300)); // 쓰기 타임아웃 300ms
        writer.start();
        registry = new SseEmitterRegistry(writer);
    }

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        writer.stop();
    }

    private static ChatMessageView view(long msgId) {
        return new ChatMessageView(msgId, "내용" + msgId, "닉", "OB", null, "2026-10-09T19:00:00.000+09:00");
    }

    private static SseFrame frame(long msgId) {
        return SseFrame.messages(List.of(view(msgId)), msgId);
    }

    private static List<Long> ids(ChatSubscription subscription) {
        return SseCapture.dataEvents(subscription.emitter()).stream()
                .filter(e -> SseFrame.MESSAGES.equals(e.name()))
                .map(e -> Long.parseLong(e.id()))
                .toList();
    }

    /** 그 구독 emitter 의 writeLock 을 다른 스레드에서 쥔다 — 이후 그 emitter 의 send/complete 는 멈춘다. */
    private void blockEmitter(ChatSubscription subscription) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("writeLock");
        field.setAccessible(true);
        Lock lock = (Lock) field.get(subscription.emitter());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "test-blocker");
        blocker.setDaemon(true);
        blocker.start();
        releases.add(release);
        blockers.add(blocker);
        assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
    }

    // ---------- 프레임 형식 ----------

    @Test
    @DisplayName("[CHAT-GC-87] messages 프레임은 event: messages · id: 마지막 msgId · data: 6필드 배열로 나간다")
    void messagesFrame_wireFormat() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        writer.deliver(subscription, SseFrame.messages(List.of(view(4398), view(4401), view(4402)), 4402));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(SseCapture.dataEvents(subscription.emitter())).hasSize(1));
        Event event = SseCapture.dataEvents(subscription.emitter()).get(0);
        assertThat(event.name()).isEqualTo("messages");
        assertThat(event.id()).isEqualTo("4402");
        assertThat(event.data()).startsWith("[{\"msgId\":4398,").contains("\"msgId\":4401", "\"msgId\":4402");
        assertThat(event.data()).contains("\"content\"", "\"senderNickname\"", "\"teamCode\"", "\"profileImgUrl\"", "\"sentAt\"")
                .doesNotContain("senderId");
    }

    @Test
    @DisplayName("[CHAT-GC-91] deleted 프레임은 event: deleted · data: {\"msgId\":n} 이고 id: 필드가 없다")
    void deletedFrame_hasNoId() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        writer.deliver(subscription, SseFrame.deleted(4200));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(SseCapture.dataEvents(subscription.emitter())).hasSize(1));
        Event event = SseCapture.dataEvents(subscription.emitter()).get(0);
        assertThat(event.name()).isEqualTo("deleted");
        assertThat(event.id()).isNull();
        assertThat(event.data()).isEqualTo("{\"msgId\":4200}");
    }

    @Test
    @DisplayName("[CHAT-GC-39] reset 프레임은 event: reset · data: {} 이다")
    void resetFrame_hasEmptyObject() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        writer.enqueue(subscription, SseFrame.reset());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(SseCapture.dataEvents(subscription.emitter())).hasSize(1));
        Event event = SseCapture.dataEvents(subscription.emitter()).get(0);
        assertThat(event.name()).isEqualTo("reset");
        assertThat(event.data()).isEqualTo("{}");
        assertThat(event.id()).isNull();
    }

    // ---------- 순서 ----------

    @Test
    @DisplayName("[CHAT-GC-87] 한 구독자에게 가는 프레임은 쓰기 풀 스레드가 여럿이어도 넣은 순서 그대로 나간다")
    void deliver_preservesOrderPerSubscriber() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        IntStream.rangeClosed(1, 200).forEach(i -> writer.deliver(subscription, frame(i)));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(ids(subscription)).hasSize(200));
        assertThat(ids(subscription)).isEqualTo(IntStream.rangeClosed(1, 200).mapToObj(Long::valueOf).toList());
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구 중 도착한 실시간 프레임은 보류되고, 복구 프레임이 먼저 나간 뒤 goLive 로 풀려 순서대로 나간다")
    void recovering_holdsRealtimeUntilGoLive() {
        ChatSubscription subscription = registry.register(ROOM, 1L, true);

        writer.deliver(subscription, frame(500));   // 복구 중 도착한 실시간
        writer.enqueue(subscription, frame(300));   // 복구 프레임

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(ids(subscription)).containsExactly(300L));
        // 보류 중인 실시간은 아직 안 나간다
        assertThat(ids(subscription)).doesNotContain(500L);

        writer.goLive(subscription);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(ids(subscription)).containsExactly(300L, 500L));
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구 프레임 enqueue 와 실시간 deliver 가 동시에 진행돼도 실시간 프레임은 하나도 빠지지 않고 전부 복구 프레임 뒤에 나간다")
    void recoveryAndRealtimeRace_noLossAndRecoveryFirst() throws Exception {
        ChatSubscription subscription = registry.register(ROOM, 1L, true);
        int realtimeCount = 200;
        CountDownLatch go = new CountDownLatch(1);
        Thread realtime = new Thread(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                return;
            }
            for (int i = 1; i <= realtimeCount; i++) {
                writer.deliver(subscription, frame(1000 + i));
            }
        });
        realtime.start();

        go.countDown();
        for (int i = 1; i <= 50; i++) {
            writer.enqueue(subscription, frame(i)); // 복구 프레임 1..50
        }
        writer.goLive(subscription);
        realtime.join(5000);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(ids(subscription)).hasSize(50 + realtimeCount));
        List<Long> sent = ids(subscription);
        assertThat(sent.subList(0, 50)).isEqualTo(IntStream.rangeClosed(1, 50).mapToObj(Long::valueOf).toList());
        assertThat(sent.subList(50, sent.size()))
                .isEqualTo(IntStream.rangeClosed(1, realtimeCount).mapToObj(i -> (long) (1000 + i)).toList());
    }

    @Test
    @DisplayName("[CHAT-GC-43] 복구와 실시간이 겹쳐 같은 msgId 가 두 번 와도 서버는 중복을 억제하지 않고 둘 다 보낸다(중복 제거는 클라이언트 책임)")
    void duplicateMsgIds_areNotSuppressedByServer() {
        ChatSubscription subscription = registry.register(ROOM, 1L, true);

        writer.enqueue(subscription, frame(120)); // 복구
        writer.deliver(subscription, frame(120)); // 같은 번호의 실시간
        writer.goLive(subscription);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(ids(subscription)).containsExactly(120L, 120L));
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구가 필요 없는 구독(recovering=false)은 deliver 한 프레임이 바로 나간다")
    void notRecovering_deliversImmediately() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        writer.deliver(subscription, frame(7));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(ids(subscription)).containsExactly(7L));
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구 중 보류 프레임이 대기열 한도(256)를 넘으면 조용히 버리지 않고 구독을 끊는다")
    void recovering_heldOverflow_closesSubscription() {
        ChatSubscription subscription = registry.register(ROOM, 1L, true);

        IntStream.rangeClosed(1, SseFrameWriter.MAX_PENDING_FRAMES + 1).forEach(i -> writer.deliver(subscription, frame(i)));

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(registry.count(ROOM)).isZero());
    }

    // ---------- 쓰기 타임아웃 격리 (89, 90) ----------

    @Test
    @DisplayName("[CHAT-GC-90] 쓰기가 막힌 구독자 하나는 write-timeout 뒤 레지스트리에서 회수되고, 같은 방의 다른 구독자 전달은 계속된다")
    void blockedSubscriber_isReclaimed_othersKeepReceiving() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        ChatSubscription healthy = registry.register(ROOM, 2L, false);
        blockEmitter(slow);

        writer.deliver(slow, frame(1));
        writer.deliver(healthy, frame(1));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(ids(healthy)).containsExactly(1L));
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(registry.subscriptions(ROOM)).containsExactly(healthy));

        writer.deliver(healthy, frame(2));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(ids(healthy)).containsExactly(1L, 2L));
    }

    @Test
    @DisplayName("[CHAT-GC-89] 막힌 구독자가 있어도 deliver/enqueue 호출 스레드(컨슈머·배처)는 기다리지 않는다")
    void blockedSubscriber_neverBlocksCallingThread() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        blockEmitter(slow);

        long start = System.nanoTime();
        for (int i = 1; i <= 100; i++) {
            writer.deliver(slow, frame(i));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("100회 deliver 에 걸린 시간").isLessThan(250);
    }

    @Test
    @DisplayName("[CHAT-GC-89] 쓰기 중인 emitter 를 닫으라는 요청(퇴장·축출)도 호출 스레드를 막지 않고 closer 스레드로 넘긴다")
    void closeOfBlockedEmitter_doesNotBlockCaller() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        blockEmitter(slow);

        long start = System.nanoTime();
        registry.closeSubscriptions(ROOM, 1L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).isLessThan(250);
        assertThat(registry.count(ROOM)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-90] 막힌 구독자가 풀려 닫기 요청이 처리되면 emitter 는 결국 complete 된다")
    void blockedEmitter_isEventuallyCompletedAfterUnblock() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        blockEmitter(slow);
        registry.closeSubscriptions(ROOM, 1L);

        releases.forEach(CountDownLatch::countDown);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            try {
                slow.emitter().send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().comment("x"));
                throw new AssertionError("아직 닫히지 않음");
            } catch (IllegalStateException closed) {
                // 닫힘
            }
        });
    }

    @Test
    @DisplayName("[CHAT-GC-90] 막힌 구독자의 대기열이 한도(256)를 넘으면 그 구독만 끊는다 — 메모리가 무한히 쌓이지 않는다")
    void blockedSubscriber_queueOverflow_closesOnlyThatSubscription() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        ChatSubscription healthy = registry.register(ROOM, 2L, false);
        blockEmitter(slow);

        IntStream.rangeClosed(1, SseFrameWriter.MAX_PENDING_FRAMES + 10).forEach(i -> writer.deliver(slow, frame(i)));

        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(registry.subscriptions(ROOM)).containsExactly(healthy));
    }

    @Test
    @DisplayName("[CHAT-GC-90] 막힌 구독자가 풀 크기보다 적게 있는 한, 정상 구독자 여러 명의 전달이 지연되지 않는다")
    void severalBlockedSubscribers_doNotStarveOthers() throws Exception {
        List<ChatSubscription> slow = new ArrayList<>();
        for (long user = 1; user <= 3; user++) {
            ChatSubscription s = registry.register("slow-" + user, user, false);
            blockEmitter(s);
            writer.deliver(s, frame(1));
            slow.add(s);
        }
        List<ChatSubscription> healthy = new ArrayList<>();
        for (long user = 10; user < 20; user++) {
            healthy.add(registry.register(ROOM, user, false));
        }

        healthy.forEach(s -> writer.deliver(s, frame(9)));

        for (ChatSubscription s : healthy) {
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(ids(s)).containsExactly(9L));
        }
    }

    // ---------- 하트비트 실패 회수 ----------

    @Test
    @DisplayName("[CHAT-GC-36] 이미 닫힌 emitter 에 쓰면 쓰기 실패로 그 구독이 회수된다")
    void writeToClosedEmitter_reclaimsSubscription() {
        ChatSubscription dead = registry.register(ROOM, 1L, false);
        dead.emitter().complete();

        writer.enqueue(dead, SseFrame.PING);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(registry.count(ROOM)).isZero());
    }
}
