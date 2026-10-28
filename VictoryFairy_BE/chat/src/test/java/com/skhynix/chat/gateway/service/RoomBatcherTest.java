package com.skhynix.chat.gateway.service;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.shared.ChatMessageView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * 배처. 쓰기 풀은 목으로 대체해 "누구에게 어떤 프레임을 맡겼는가"만 본다.
 */
class RoomBatcherTest {

    private static final String ROOM = "G1";

    private SseFrameWriter writer;
    private SseEmitterRegistry registry;
    private RoomBatcher batcher;

    @BeforeEach
    void setUp() {
        writer = mock(SseFrameWriter.class);
        registry = new SseEmitterRegistry(writer);
        batcher = new RoomBatcher(registry, writer, props(150, 1000));
    }

    @AfterEach
    void tearDown() {
        batcher.stop();
    }

    private static ChatMessageView view(long msgId) {
        return new ChatMessageView(msgId, "내용" + msgId, "닉", "OB", null, "t");
    }

    @SuppressWarnings("unchecked")
    private static List<Long> msgIds(SseFrame frame) {
        return ((List<ChatMessageView>) frame.data()).stream().map(ChatMessageView::msgId).toList();
    }

    private List<SseFrame> deliveredTo(ChatSubscription subscription) {
        ArgumentCaptor<SseFrame> captor = ArgumentCaptor.forClass(SseFrame.class);
        verify(writer, org.mockito.Mockito.atLeast(0)).deliver(org.mockito.ArgumentMatchers.eq(subscription), captor.capture());
        return captor.getAllValues();
    }

    // ---------- 묶음 ----------

    @Test
    @DisplayName("[CHAT-GC-87] 한 틱에 도착한 메시지 3건은 offset 오름차순 배열의 messages 이벤트 1개이고 id 는 마지막 msgId 이다(번호가 띄엄띄엄해도 그대로)")
    void flush_threeMessages_oneFrameWithLastOffsetAsId() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.enqueueMessage(ROOM, 9L, view(4398));
        batcher.enqueueMessage(ROOM, 9L, view(4401));
        batcher.enqueueMessage(ROOM, 9L, view(4402));

        batcher.flush();

        List<SseFrame> frames = deliveredTo(listener);
        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).name()).isEqualTo("messages");
        assertThat(frames.get(0).id()).isEqualTo("4402");
        assertThat(msgIds(frames.get(0))).containsExactly(4398L, 4401L, 4402L);
    }

    @Test
    @DisplayName("[CHAT-GC-87] 큐는 한 틱마다 비워진다 — 다음 틱에는 이전 메시지가 다시 나가지 않는다")
    void flush_drainsQueue() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.enqueueMessage(ROOM, 9L, view(1));
        batcher.flush();
        batcher.flush();

        assertThat(deliveredTo(listener)).hasSize(1);
    }

    @Test
    @DisplayName("[CHAT-GC-87] 도착한 메시지가 없으면 어떤 구독자에게도 프레임을 맡기지 않는다")
    void flush_nothingQueued_deliversNothing() {
        registry.register(ROOM, 2L, false);

        batcher.flush();

        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("[CHAT-GC-87] 방이 달라도 그 방 구독자에게만 간다")
    void flush_deliversOnlyToThatRoom() {
        ChatSubscription inRoom = registry.register(ROOM, 2L, false);
        ChatSubscription elsewhere = registry.register("OTHER", 3L, false);
        batcher.enqueueMessage(ROOM, 9L, view(1));

        batcher.flush();

        assertThat(deliveredTo(inRoom)).hasSize(1);
        assertThat(deliveredTo(elsewhere)).isEmpty();
    }

    @Test
    @DisplayName("구독자가 없는 방의 메시지는 조용히 버려지고 나중에 합류한 구독자에게 옛 메시지가 나가지 않는다")
    void flush_noSubscribers_dropsAndDoesNotReplayLater() {
        batcher.enqueueMessage(ROOM, 9L, view(1));
        batcher.flush();

        ChatSubscription late = registry.register(ROOM, 2L, false);
        batcher.flush();

        assertThat(deliveredTo(late)).isEmpty();
    }

    // ---------- 발신자 제외 (67, 88) ----------

    @Test
    @DisplayName("[CHAT-GC-88] 배치가 발신자 A 의 메시지 1건뿐이면 A 는 아무 프레임도 받지 않고 B 는 받는다")
    void flush_onlySenderMessages_senderGetsNothingOthersGet() {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        ChatSubscription b = registry.register(ROOM, 2L, false);
        batcher.enqueueMessage(ROOM, 1L, view(10));

        batcher.flush();

        assertThat(deliveredTo(a)).isEmpty();
        assertThat(deliveredTo(b)).hasSize(1);
        assertThat(msgIds(deliveredTo(b).get(0))).containsExactly(10L);
    }

    @Test
    @DisplayName("[CHAT-GC-88] 같은 배치라도 구독자마다 배열이 다르다 — 각자 자기 메시지만 빠지고 id 는 각자 남은 마지막 msgId 이다")
    void flush_mixedBatch_eachSubscriberGetsOwnArray() {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        ChatSubscription b = registry.register(ROOM, 2L, false);
        ChatSubscription c = registry.register(ROOM, 3L, false);
        batcher.enqueueMessage(ROOM, 2L, view(10)); // B
        batcher.enqueueMessage(ROOM, 1L, view(11)); // A
        batcher.enqueueMessage(ROOM, 2L, view(12)); // B

        batcher.flush();

        SseFrame forA = deliveredTo(a).get(0);
        SseFrame forB = deliveredTo(b).get(0);
        SseFrame forC = deliveredTo(c).get(0);
        assertThat(msgIds(forA)).containsExactly(10L, 12L);
        assertThat(forA.id()).isEqualTo("12");
        assertThat(msgIds(forB)).containsExactly(11L);
        assertThat(forB.id()).isEqualTo("11");
        assertThat(msgIds(forC)).containsExactly(10L, 11L, 12L);
        assertThat(forC.id()).isEqualTo("12");
    }

    @Test
    @DisplayName("[CHAT-GC-88] 자기 메시지가 배열 마지막이면 id 는 배치 마지막이 아니라 그 구독자에게 남은 마지막 msgId 이다")
    void flush_senderMessageIsLast_idIsLastRemaining() {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        batcher.enqueueMessage(ROOM, 2L, view(10));
        batcher.enqueueMessage(ROOM, 1L, view(11));

        batcher.flush();

        SseFrame forA = deliveredTo(a).get(0);
        assertThat(msgIds(forA)).containsExactly(10L);
        assertThat(forA.id()).isEqualTo("10");
    }

    @Test
    @DisplayName("[CHAT-GC-14] 구독자에게 맡기는 프레임 항목에는 발신자 id 가 없다")
    void flush_frameItemsCarryNoSenderId() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.enqueueMessage(ROOM, 9L, view(1));

        batcher.flush();

        Object first = ((List<?>) deliveredTo(listener).get(0).data()).get(0);
        assertThat(first).isInstanceOf(ChatMessageView.class);
        assertThat(ChatMessageView.class.getRecordComponents()).extracting("name").doesNotContain("senderId");
    }

    // ---------- 툼스톤 (91) ----------

    @Test
    @DisplayName("[CHAT-GC-91] blind 툼스톤은 발신자 포함 방의 모든 구독자에게 deleted 이벤트(id 없음)로 간다")
    void flush_tombstone_goesToEveryoneIncludingSender() {
        ChatSubscription author = registry.register(ROOM, 1L, false);
        ChatSubscription other = registry.register(ROOM, 2L, false);
        batcher.enqueueDeleted(ROOM, 4200);

        batcher.flush();

        for (ChatSubscription s : List.of(author, other)) {
            List<SseFrame> frames = deliveredTo(s);
            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).name()).isEqualTo("deleted");
            assertThat(frames.get(0).id()).isNull();
            assertThat(frames.get(0).data()).isEqualTo(java.util.Map.of("msgId", 4200L));
        }
    }

    @Test
    @DisplayName("[CHAT-GC-91] 메시지 사이에 툼스톤이 끼면 도착 순서대로 messages → deleted → messages 로 나눠 나간다")
    void flush_tombstoneBetweenMessages_preservesArrivalOrder() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.enqueueMessage(ROOM, 9L, view(1));
        batcher.enqueueMessage(ROOM, 9L, view(2));
        batcher.enqueueDeleted(ROOM, 1);
        batcher.enqueueMessage(ROOM, 9L, view(3));

        batcher.flush();

        List<SseFrame> frames = deliveredTo(listener);
        assertThat(frames).extracting(SseFrame::name).containsExactly("messages", "deleted", "messages");
        assertThat(msgIds(frames.get(0))).containsExactly(1L, 2L);
        assertThat(msgIds(frames.get(2))).containsExactly(3L);
    }

    @Test
    @DisplayName("[CHAT-GC-91] 툼스톤이 먼저 도착하고 메시지가 뒤따르면 deleted 가 messages 보다 먼저 나간다")
    void flush_tombstoneFirst_isFirst() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.enqueueDeleted(ROOM, 7);
        batcher.enqueueMessage(ROOM, 9L, view(8));

        batcher.flush();

        assertThat(deliveredTo(listener)).extracting(SseFrame::name).containsExactly("deleted", "messages");
    }

    @Test
    @DisplayName("[CHAT-GC-88] 발신자 제외로 한 messages 구간이 비어도 사이의 deleted 는 그 구독자에게 그대로 간다")
    void flush_senderOnlyRunBetweenTombstones_stillDeliversTombstones() {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        batcher.enqueueDeleted(ROOM, 5);
        batcher.enqueueMessage(ROOM, 1L, view(6));
        batcher.enqueueDeleted(ROOM, 6);

        batcher.flush();

        assertThat(deliveredTo(a)).extracting(SseFrame::name).containsExactly("deleted", "deleted");
    }

    // ---------- 필터 없음 (93, 94) ----------

    @Test
    @DisplayName("[CHAT-GC-94] 배처는 차단·blind 정보를 아예 알지 못한다 — 실시간 전달에 필터를 적용하지 않는다")
    void batcher_hasNoBlockOrBlindDependency() {
        assertThat(java.util.Arrays.stream(RoomBatcher.class.getDeclaredConstructors()[0].getParameterTypes())
                .map(Class::getSimpleName))
                .containsExactly("SseEmitterRegistry", "SseFrameWriter", "ChatProperties");
    }

    @Test
    @DisplayName("[CHAT-GC-93] sampling-threshold-per-sec 를 1 로 두고 100건을 보내도 전부 전달된다")
    void flush_samplingThresholdIsIgnored() {
        ChatProperties base = props();
        ChatProperties sampled = new ChatProperties(base.role(), base.history(), base.recovery(),
                new ChatProperties.Gateway(150, 1000, 1), base.rateLimit(), base.dedup(), base.kafka());
        RoomBatcher sampling = new RoomBatcher(registry, writer, sampled);
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        for (long i = 1; i <= 100; i++) {
            sampling.enqueueMessage(ROOM, 9L, view(i));
        }

        sampling.flush();

        assertThat(msgIds(deliveredTo(listener).get(0))).hasSize(100);
    }

    // ---------- 견고성 ----------

    @Test
    @DisplayName("한 방의 전달이 예외를 던져도 다른 방은 같은 틱에서 계속 전달된다")
    void flush_failureInOneRoom_doesNotBlockOthers() {
        ChatSubscription bad = registry.register("BAD", 1L, false);
        ChatSubscription good = registry.register("GOOD", 2L, false);
        doThrow(new IllegalStateException("boom")).when(writer).deliver(org.mockito.ArgumentMatchers.eq(bad), any());
        batcher.enqueueMessage("BAD", 9L, view(1));
        batcher.enqueueMessage("GOOD", 9L, view(2));

        batcher.flush();

        verify(writer, times(1)).deliver(org.mockito.ArgumentMatchers.eq(good), any());
    }

    @Test
    @DisplayName("[CHAT-GC-89] 컨슈머 스레드 여러 개가 동시에 적재하면서 틱이 도는 동안에도 메시지가 유실·중복 없이 방 안 순서대로 전달된다")
    void enqueueDuringFlush_noLossNoDuplicate() throws Exception {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        org.mockito.Mockito.doAnswer(invocation -> {
            SseFrame frame = invocation.getArgument(1);
            received.addAll(msgIds(frame));
            return null;
        }).when(writer).deliver(org.mockito.ArgumentMatchers.eq(listener), any());

        int producers = 4;
        int perProducer = 2000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        // 단일 파티션처럼 한 방의 도착 순서는 하나의 생산 스레드가 정한다. 여기서는 생산자별 구간을 나눠
        // 각 생산자 안의 순서가 유지되는지 + 총합이 맞는지를 본다.
        for (int p = 0; p < producers; p++) {
            long base = p * 100_000L;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    batcher.enqueueMessage(ROOM, 9L, view(base + i));
                }
            });
            threads.add(t);
            t.start();
        }
        Thread flusher = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!done.get()) {
                batcher.flush();
            }
        });
        flusher.start();

        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        done.set(true);
        flusher.join();
        batcher.flush();

        assertThat(received).hasSize(producers * perProducer).doesNotHaveDuplicates();
        for (int p = 0; p < producers; p++) {
            long base = p * 100_000L;
            List<Long> mine = received.stream().filter(id -> id >= base && id < base + 100_000L).toList();
            assertThat(mine).isSorted();
        }
    }

    // ---------- 주기 실행 ----------

    @Test
    @DisplayName("[CHAT-GC-87] start 후에는 batch-interval-ms 마다 스스로 큐를 비워 전달한다")
    void start_flushesPeriodically() {
        ChatProperties base = props();
        ChatProperties fast = new ChatProperties(base.role(), base.history(), base.recovery(),
                new ChatProperties.Gateway(30, 1000, 0), base.rateLimit(), base.dedup(), base.kafka());
        RoomBatcher periodic = new RoomBatcher(registry, writer, fast);
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        periodic.start();
        try {
            periodic.enqueueMessage(ROOM, 9L, view(1));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(deliveredTo(listener)).hasSize(1));
            assertThat(periodic.isRunning()).isTrue();
        } finally {
            periodic.stop();
        }
        assertThat(periodic.isRunning()).isFalse();
    }

    @Test
    @DisplayName("정지(stop) 시 남은 큐를 한 번 더 비워 전달하고 끝낸다")
    void stop_flushesRemainingQueue() {
        ChatSubscription listener = registry.register(ROOM, 2L, false);
        batcher.start();
        batcher.enqueueMessage(ROOM, 9L, view(1));

        batcher.stop();

        assertThat(deliveredTo(listener)).hasSize(1);
        verify(writer, never()).complete(any());
    }
}
