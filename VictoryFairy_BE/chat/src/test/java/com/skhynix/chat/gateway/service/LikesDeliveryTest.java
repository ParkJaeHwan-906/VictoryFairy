package com.skhynix.chat.gateway.service;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.support.SseCapture;
import com.skhynix.chat.support.SseCapture.Event;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 진짜 쓰기 풀·레지스트리 위에서 likes 프레임이 emitter 에 쓰이는 모양과 보류/타임아웃 동작을 본다.
 * 서블릿 컨테이너 없이 {@link SseCapture} 로 "쓰려고 시도한 와이어 형식"을 읽는다.
 */
class LikesDeliveryTest {

    private static final String ROOM = "G1";

    private SseFrameWriter writer;
    private SseEmitterRegistry registry;
    private LikesThrottler throttler;
    private final List<CountDownLatch> releases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        writer = new SseFrameWriter(props(150, 300));
        writer.start();
        registry = new SseEmitterRegistry(writer);
        throttler = new LikesThrottler(registry, writer,
                new ChatLikesProperties(new ChatLikesProperties.RateLimit(10), 300, 100, 10_000));
        throttler.start();
    }

    @AfterEach
    void tearDown() {
        releases.forEach(CountDownLatch::countDown);
        throttler.stop();
        writer.stop();
    }

    private static List<Event> likes(ChatSubscription subscription) {
        return SseCapture.dataEvents(subscription.emitter()).stream().filter(e -> "likes".equals(e.name())).toList();
    }

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
        assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("[CHAT-LK-27][CHAT-LK-28] likes 프레임은 event: likes 와 data: 구단 코드 문자열 배열 두 줄뿐이고 id: 가 없다")
    void wireFormat_eventAndDataOnly_noId() {
        ChatSubscription s = registry.register(ROOM, 1L, false);

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(2)).until(() -> likes(s).size() == 1);
        Event event = likes(s).get(0);
        assertThat(event.name()).isEqualTo("likes");
        assertThat(event.id()).isNull();
        assertThat(event.data()).isEqualTo("[\"HT\"]");
    }

    @Test
    @DisplayName("[CHAT-LK-28] likes 는 마지막 messages 의 id 를 바꾸지 않는다 — messages 뒤에 likes 가 와도 messages 프레임의 id 만 남는다")
    void likesDoNotCarryIdSoLastEventIdStaysOnMessages() {
        ChatSubscription s = registry.register(ROOM, 1L, false);
        writer.deliver(s, SseFrame.messages(List.of(new com.skhynix.chat.shared.ChatMessageView(
                4402, "내용", "닉", "OB", null, "2026-10-09T19:00:00.000+09:00")), 4402));
        throttler.offer(ROOM, "LG");

        await().atMost(Duration.ofSeconds(2)).until(() -> SseCapture.dataEvents(s.emitter()).size() == 2);
        List<Event> events = SseCapture.dataEvents(s.emitter());
        assertThat(events).filteredOn(e -> e.id() != null).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("messages");
            assertThat(e.id()).isEqualTo("4402");
        });
    }

    @Test
    @DisplayName("[CHAT-LK-29] Last-Event-ID 복구 중인 구독자의 likes 는 복구가 끝날 때(goLive)까지 붙잡혀 있다가 그 뒤에 전달된다")
    void recovering_likesHeldUntilGoLive() throws Exception {
        ChatSubscription recovering = registry.register(ROOM, 1L, true);
        ChatSubscription live = registry.register(ROOM, 2L, false);

        throttler.offer(ROOM, "HT");

        await().atMost(Duration.ofSeconds(2)).until(() -> likes(live).size() == 1);
        Thread.sleep(300);
        assertThat(likes(recovering)).as("복구 프레임보다 먼저 나가면 안 된다").isEmpty();

        writer.enqueue(recovering, SseFrame.reset());
        writer.goLive(recovering);

        await().atMost(Duration.ofSeconds(2)).until(() -> likes(recovering).size() == 1);
        List<Event> events = SseCapture.dataEvents(recovering.emitter());
        assertThat(events.get(0).name()).as("복구 프레임이 먼저").isEqualTo("reset");
        assertThat(events.get(1).name()).isEqualTo("likes");
        assertThat(events.get(1).data()).isEqualTo("[\"HT\"]");
    }

    @Test
    @DisplayName("[CHAT-LK-34] likes 쓰기가 write-timeout(300ms)을 넘긴 구독자는 그 스트림만 회수되고, 같은 방 다른 구독자는 계속 likes 를 받는다")
    void slowSubscriberClosedAlone_othersKeepReceiving() throws Exception {
        ChatSubscription slow = registry.register(ROOM, 1L, false);
        ChatSubscription healthy = registry.register(ROOM, 2L, false);
        blockEmitter(slow);

        throttler.offer(ROOM, "HT");
        await().atMost(Duration.ofSeconds(2)).until(() -> likes(healthy).size() == 1);

        await().atMost(Duration.ofSeconds(5)).until(() -> registry.count(ROOM) == 1);
        assertThat(registry.subscriptions(ROOM)).containsExactly(healthy);

        Thread.sleep(150);
        throttler.offer(ROOM, "LG");
        await().atMost(Duration.ofSeconds(2)).until(() -> likes(healthy).size() == 2);
        assertThat(likes(healthy).get(1).data()).isEqualTo("[\"LG\"]");
    }
}
