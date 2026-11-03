package com.skhynix.chat.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.chat.global.config.ChatPresenceProperties;
import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

/**
 * 집계기의 판단 로직(보낼지 말지·누구를 지울지·어느 스레드에서 하는지)을 Redis·소켓 없이 확인한다.
 * 스토어와 쓰기 풀은 목이고 레지스트리는 실제다. 주기 tick 은 직접 부른다(2초를 기다리지 않는다).
 */
class PresenceTrackerTest {

    private static final String ROOM = "G1";

    private PresenceStore store;
    private SseFrameWriter writer;
    private SseEmitterRegistry registry;
    private PresenceTracker tracker;

    @BeforeEach
    void setUp() {
        store = mock(PresenceStore.class);
        writer = mock(SseFrameWriter.class);
        registry = new SseEmitterRegistry(writer);
        tracker = new PresenceTracker(registry, writer, store, new ChatPresenceProperties(45, 2000, 90),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        tracker.stop();
    }

    private ChatSubscription join(String room, long user, long countReturnedByJoin) {
        when(store.join(eq(room), anyList(), anyString())).thenReturn(countReturnedByJoin);
        ChatSubscription subscription = registry.register(room, user, false);
        registry.joined(subscription);
        return subscription;
    }

    private static long countOf(SseFrame frame) {
        assertThat(frame.name()).isEqualTo("presence");
        return ((Number) ((Map<?, ?>) frame.data()).get("count")).longValue();
    }

    private List<Long> deliveredCounts(ChatSubscription subscription) {
        ArgumentCaptor<SseFrame> captor = ArgumentCaptor.forClass(SseFrame.class);
        verify(writer, org.mockito.Mockito.atLeast(0)).deliver(eq(subscription), captor.capture());
        return captor.getAllValues().stream().map(PresenceTrackerTest::countOf).toList();
    }

    // ---------- 전달: 구독 직후 1건, 값이 바뀔 때만 ----------

    @Test
    @DisplayName("[CHAT-PR-11] 구독이 성립하면 그 구독자에게만 현재 인원 1건을 보내고, 같은 방의 다른 구독자에게는 보내지 않는다")
    void joinSendsInitialValueToJoinerOnly() {
        ChatSubscription a = join(ROOM, 1L, 1);
        ChatSubscription b = join(ROOM, 2L, 2);

        assertThat(deliveredCounts(a)).containsExactly(1L);
        assertThat(deliveredCounts(b)).containsExactly(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-12] 집계 값이 마지막으로 보낸 값과 달라지면 tick 이 그 방 구독자 전원에게 새 값을 보낸다")
    void tickBroadcastsChangedValueToEverySubscriber() {
        ChatSubscription a = join(ROOM, 1L, 1);
        ChatSubscription b = join(ROOM, 2L, 2);
        when(store.count(ROOM)).thenReturn(3L);

        tracker.tick();

        assertThat(deliveredCounts(a)).containsExactly(1L, 3L);
        assertThat(deliveredCounts(b)).containsExactly(2L, 3L);
        assertThat(a.presenceSent()).isEqualTo(3L);
    }

    @Test
    @DisplayName("[CHAT-PR-14] 집계 값이 마지막으로 보낸 값과 같으면 아무 것도 보내지 않는다 — 변화 없는 방에서 tick 을 여러 번 돌려도 0프레임")
    void tickSendsNothingWhenUnchanged() {
        ChatSubscription a = join(ROOM, 1L, 2);
        when(store.count(ROOM)).thenReturn(2L);

        for (int i = 0; i < 20; i++) {
            tracker.tick();
        }

        assertThat(deliveredCounts(a)).containsExactly(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-13] 한 주기 동안 500명이 입장해도 기존 구독자는 tick 한 번에 최종 인원 1프레임만 받는다(1,2,...,500 프레임이 아니다)")
    void burstOfJoinsIsCoalescedIntoOneFramePerTick() {
        ChatSubscription existing = join(ROOM, 1L, 1);
        for (long user = 2; user <= 501; user++) {
            join(ROOM, user, user);   // 신규 구독자는 자기 초기 값만 받는다
        }
        when(store.count(ROOM)).thenReturn(501L);

        tracker.tick();

        assertThat(deliveredCounts(existing)).as("초기 1건 + 이번 주기 1건").containsExactly(1L, 501L);
    }

    @Test
    @DisplayName("[CHAT-PR-17] presence 프레임은 집계 스레드가 소켓에 쓰지 않고 쓰기 풀(deliver)에 넘긴다 — 호출 스레드는 chat-presence 이거나 요청 스레드가 아니다")
    void framesAreHandedToWriterNotWrittenToSocket() {
        AtomicReference<String> deliverThread = new AtomicReference<>();
        doAnswer(inv -> {
            deliverThread.set(Thread.currentThread().getName());
            return null;
        }).when(writer).deliver(any(), any());
        when(store.join(eq(ROOM), anyList(), anyString())).thenReturn(1L);

        ChatSubscription a = registry.register(ROOM, 1L, false);
        registry.joined(a);

        assertThat(deliverThread.get()).isEqualTo("chat-presence");
        // 쓰기 풀 경유 외의 경로(emitter 에 직접 send)가 없다: 목 쓰기 풀이 받은 호출만 있고 emitter 는 핸들러가 없어 쓴 흔적이 없다
        verify(writer, never()).enqueue(any(), any());
        assertThat(com.skhynix.chat.support.SseCapture.events(a.emitter())).isEmpty();
    }

    // ---------- 장애: 생략 ----------

    @Test
    @DisplayName("[CHAT-PR-23] 주기 집계의 Redis 명령이 실패하면 그 주기에는 아무것도 보내지 않고 마지막 값(presenceSent)을 그대로 둔다")
    void tickRedisFailureSendsNothingAndKeepsLastValue() {
        ChatSubscription a = join(ROOM, 1L, 2);
        when(store.count(ROOM)).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> tracker.tick()).doesNotThrowAnyException();

        assertThat(deliveredCounts(a)).containsExactly(2L);
        assertThat(a.presenceSent()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-23] 실패한 뒤 파드 로컬 값(구독자 수)이나 null count 로 대체해 보내지 않는다 — 복구 뒤 Redis 값만 나간다")
    void noFallbackToLocalValueThenRecoversWithRedisValue() {
        ChatSubscription a = join(ROOM, 1L, 2);
        join(ROOM, 2L, 2);
        join(ROOM, 3L, 2);
        when(store.count(ROOM)).thenThrow(new RuntimeException("redis down")).thenReturn(7L);

        tracker.tick();
        assertThat(deliveredCounts(a)).as("로컬 구독자 수(3)가 아니다").containsExactly(2L);

        tracker.tick();
        assertThat(deliveredCounts(a)).as("복구 뒤 실제 값").containsExactly(2L, 7L);
    }

    @Test
    @DisplayName("[CHAT-PR-25] 구독 직후 등록(join)의 Redis 명령이 실패해도 joined() 는 예외 없이 돌아오고 초기 presence 만 생략된다. 구독은 열린 채다")
    void joinRedisFailureOmitsInitialFrameButKeepsSubscription() {
        when(store.join(eq(ROOM), anyList(), anyString())).thenThrow(new RuntimeException("redis down"));

        ChatSubscription a = registry.register(ROOM, 1L, false);
        assertThatCode(() -> registry.joined(a)).doesNotThrowAnyException();

        verify(writer, never()).deliver(any(), any());
        assertThat(registry.subscriptions(ROOM)).containsExactly(a);
        assertThat(a.presenceSent()).as("등록 전(-2)이 아니라 값 미상(-1) — 다음 주기가 값을 보낸다")
                .isEqualTo(ChatSubscription.PRESENCE_UNKNOWN);
    }

    @Test
    @DisplayName("[CHAT-PR-26] 등록 실패로 값 미상인 구독자도 Redis 가 돌아온 다음 tick 에 실제 인원을 받는다(재시작 없이)")
    void unknownValueSubscriberReceivesActualCountAfterRecovery() {
        when(store.join(eq(ROOM), anyList(), anyString())).thenThrow(new RuntimeException("redis down"));
        ChatSubscription a = registry.register(ROOM, 1L, false);
        registry.joined(a);
        when(store.count(ROOM)).thenReturn(4L);

        tracker.tick();

        assertThat(deliveredCounts(a)).containsExactly(4L);
    }

    @Test
    @DisplayName("[CHAT-PR-24] 하트비트 스레드가 부르는 heartbeat() 는 Redis 왕복이 5초 걸려도 즉시 돌아온다(:ping 적재와 방 수명 작업이 밀리지 않는다)")
    void heartbeatNeverBlocksOnRedis() {
        join(ROOM, 1L, 1);
        doAnswer(inv -> {
            Thread.sleep(3_000);
            return null;
        }).when(store).refresh(anyString(), anyList(), anyString());

        long start = System.nanoTime();
        registry.heartbeat();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(300);
    }

    @Test
    @DisplayName("[CHAT-PR-6] 하트비트마다 이 파드의 방별 로컬 구독 계정(중복 제거)과 instanceId 로 생존 갱신을 맡긴다")
    void heartbeatTriggersRefreshForDistinctLocalUsers() {
        join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);
        join("G2", 3L, 1);

        registry.heartbeat();

        ArgumentCaptor<List<Long>> g1 = ArgumentCaptor.forClass(List.class);
        verify(store, timeout(2000)).refresh(eq(ROOM), g1.capture(), eq(registry.instanceId()));
        assertThat(g1.getValue()).containsExactlyInAnyOrder(1L, 2L);
        verify(store, timeout(2000)).refresh(eq("G2"), eq(List.of(3L)), eq(registry.instanceId()));
    }

    // ---------- 종료 시 제거 ----------

    @Test
    @DisplayName("[CHAT-PR-8] 퇴장(DELETE 에 해당하는 closeSubscriptions)한 계정은 소유자 확인 삭제를 이 파드 instanceId 로 요청한다")
    void explicitLeaveRequestsOwnerCheckedRemoval() {
        join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);

        registry.closeSubscriptions(ROOM, 2L);

        verify(store, timeout(2000)).leave(ROOM, List.of(2L), registry.instanceId());
        verify(store, never()).leave(eq(ROOM), eq(List.of(1L)), anyString());
    }

    @Test
    @DisplayName("[CHAT-PR-8] 다른 방으로 이동(전 방 구독 종료)하면 원래 방에서 그 계정의 제거를 요청한다")
    void movingToAnotherRoomRequestsRemovalFromOldRoom() {
        join(ROOM, 1L, 1);

        ChatSubscription moved = registry.register("G2", 1L, false);   // last-one-wins: 전 방 구독을 닫는다
        registry.joined(moved);

        verify(store, timeout(2000)).leave(ROOM, List.of(1L), registry.instanceId());
        verify(store, never()).leave(eq("G2"), anyList(), anyString());
    }

    @Test
    @DisplayName("[CHAT-PR-8] 파드 정상 종료(레지스트리 stop → 집계기 stop)는 열린 구독 전원의 제거를 Redis 에 반영하고 끝난다")
    void gracefulShutdownFlushesAllLeaves() {
        join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);

        registry.stop();
        tracker.stop();

        ArgumentCaptor<List<Long>> users = ArgumentCaptor.forClass(List.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).leave(eq(ROOM), users.capture(), eq(registry.instanceId()));
        List<Long> removed = new ArrayList<>();
        users.getAllValues().forEach(removed::addAll);
        assertThat(removed).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("[CHAT-PR-9] 같은 파드에서 같은 방을 재구독하면 옛 구독이 종료돼도 제거를 요청하지 않는다(자기 파드에 같은 계정의 같은 방 구독이 남아 있다)")
    void resubscribeOnSamePodDoesNotRemove() {
        join(ROOM, 1L, 1);

        ChatSubscription second = join(ROOM, 1L, 1);   // register 가 첫 구독을 축출한다

        verify(store, after(800).never()).leave(anyString(), anyList(), anyString());
        assertThat(registry.subscriptions(ROOM)).containsExactly(second);
    }

    @Test
    @DisplayName("[CHAT-PR-8] 같은 구독이 두 번 떨어졌다고 알려져도(콜백 중복) 제거 요청은 한 번만 나간다")
    void duplicateDepartureIsReportedOnce() {
        join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);

        registry.closeSubscriptions(ROOM, 2L);
        registry.closeSubscriptions(ROOM, 2L);
        registry.closeAllSubscriptions(2L);

        verify(store, timeout(2000).times(1)).leave(ROOM, List.of(2L), registry.instanceId());
        verify(store, after(500).times(1)).leave(eq(ROOM), anyList(), anyString());
    }

    @Test
    @DisplayName("[CHAT-PR-8] 제거 요청이 Redis 실패로 던져져도 집계기 스레드는 죽지 않고 다음 작업을 계속 처리한다")
    void leaveFailureDoesNotKillPresenceThread() {
        join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);
        doAnswer(inv -> {
            throw new RuntimeException("redis down");
        }).doNothing().when(store).leave(anyString(), anyList(), anyString());

        registry.closeSubscriptions(ROOM, 1L);
        verify(store, timeout(2000).times(1)).leave(eq(ROOM), eq(List.of(1L)), anyString());
        registry.closeSubscriptions(ROOM, 2L);

        verify(store, timeout(2000)).leave(eq(ROOM), eq(List.of(2L)), anyString());
    }

    // ---------- 로그 ----------

    @Test
    @DisplayName("[CHAT-PR-27] Redis 가 계속 실패해도 presence WARN 은 10초에 1줄이고 줄마다 직전 로그 이후 누적 건수가 실린다")
    void redisFailureWarnIsThrottledWithCount() {
        Logger logger = (Logger) LoggerFactory.getLogger(PresenceTracker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            join(ROOM, 1L, 1);
            when(store.count(ROOM)).thenThrow(new RuntimeException("redis down"));

            for (int i = 0; i < 200; i++) {
                tracker.tick();
            }

            List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warns).as("200번 실패했지만 10초 안이므로 1줄").hasSize(1);
            assertThat(warns.get(0).getFormattedMessage()).contains("직전 로그 이후 1건");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }


    // ---------- 2차 개정: 로컬 하한(PR-31) · 값 못 받은 구독자(PR-32) ----------

    @Test
    @DisplayName("[CHAT-PR-31] 집계 값이 이 파드의 로컬 고유 계정 수(3)보다 작으면(0, 1, 2) 보내지 않고 마지막 값 3 을 그대로 둔다. 로컬 값으로 대신 보내지도 않는다")
    void tickBelowLocalFloorIsOmitted() {
        ChatSubscription a = join(ROOM, 1L, 1);
        ChatSubscription b = join(ROOM, 2L, 2);
        ChatSubscription c = join(ROOM, 3L, 3);
        when(store.count(ROOM)).thenReturn(0L, 1L, 2L, 3L);

        for (int i = 0; i < 4; i++) {
            tracker.tick();
        }

        assertThat(deliveredCounts(c)).containsExactly(3L);
        assertThat(deliveredCounts(a)).containsExactly(1L, 3L);
        assertThat(deliveredCounts(b)).containsExactly(2L, 3L);
        assertThat(c.presenceSent()).isEqualTo(3L);
    }

    @Test
    @DisplayName("[CHAT-PR-31] 집계 값이 로컬 수와 같거나 크면 이 규칙은 아무것도 거르지 않는다 - 다른 파드 몫이 있어 5가 되면 그대로 나간다")
    void tickAtOrAboveFloorIsNotFiltered() {
        ChatSubscription a = join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);
        when(store.count(ROOM)).thenReturn(2L, 5L);

        tracker.tick();
        tracker.tick();

        assertThat(deliveredCounts(a)).containsExactly(1L, 2L, 5L);
    }

    @Test
    @DisplayName("[CHAT-PR-31] 구독 직후 초기 값이 로컬 하한 미만이면 초기 presence 를 보내지 않는다(값 미상으로 남는다)")
    void initialValueBelowFloorIsOmitted() {
        join(ROOM, 1L, 2);
        ChatSubscription b = join(ROOM, 2L, 1);   // Redis 가 1 이라고 답했지만 이 파드에는 이미 2명이 있다

        assertThat(deliveredCounts(b)).isEmpty();
        assertThat(b.presenceSent()).isEqualTo(ChatSubscription.PRESENCE_UNKNOWN);
    }

    @Test
    @DisplayName("[CHAT-PR-32] 초기 값이 PR-31 로 생략된 구독자는 다음 유효 주기에 1건 받는다 - 값이 파드의 마지막 값(2)과 같아도 받고, 이미 받은 구독자는 받지 않는다(PR-14)")
    void omittedInitialValueIsSentOnNextValidTickEvenIfEqualToPodLastValue() {
        ChatSubscription a = join(ROOM, 1L, 2);   // 파드 마지막 값 2
        ChatSubscription b = join(ROOM, 2L, 1);   // 생략됨
        when(store.count(ROOM)).thenReturn(2L);

        tracker.tick();
        tracker.tick();

        assertThat(deliveredCounts(b)).as("값이 같아도 한 번 받고, 그 뒤엔 PR-14").containsExactly(2L);
        assertThat(deliveredCounts(a)).as("이미 받았고 값이 같으면 프레임 없음").containsExactly(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-32] 생략된 구독자는 하한 미만 주기(PR-31)에는 계속 못 받고, 키가 다시 채워져 하한 이상이 된 첫 주기에 받는다")
    void omittedSubscriberWaitsForValidTick() {
        ChatSubscription a = join(ROOM, 1L, 2);
        ChatSubscription b = join(ROOM, 2L, 1);
        when(store.count(ROOM)).thenReturn(0L, 1L, 2L);

        tracker.tick();
        tracker.tick();
        assertThat(deliveredCounts(b)).isEmpty();
        tracker.tick();

        assertThat(deliveredCounts(b)).containsExactly(2L);
        assertThat(deliveredCounts(a)).containsExactly(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-25][CHAT-PR-32] 초기 값 조회가 Redis 실패로 생략된 구독자는 Redis 가 돌아온 뒤 첫 주기에, 값이 파드 마지막 값과 같아도 1건 받는다")
    void redisFailureOmittedInitialValueIsSentAfterRecoveryEvenIfSameAsPodLast() {
        ChatSubscription a = join(ROOM, 1L, 2);
        when(store.join(eq(ROOM), anyList(), anyString())).thenThrow(new RuntimeException("redis down"));
        ChatSubscription b = registry.register(ROOM, 2L, false);
        registry.joined(b);
        when(store.count(ROOM)).thenReturn(2L);

        tracker.tick();

        assertThat(deliveredCounts(b)).containsExactly(2L);
        assertThat(deliveredCounts(a)).containsExactly(2L);
    }

    @Test
    @DisplayName("[CHAT-PR-26] Redis 복구 중 로컬 고유 계정 수(2)보다 작은 값(0, 1)은 나가지 않고, 복구 뒤 실제 값(5)이 정지 전 값과 다르면 새 프레임으로 나간다")
    void recoveryNeverSendsBelowLocalAccounts() {
        ChatSubscription a = join(ROOM, 1L, 1);
        join(ROOM, 2L, 2);
        when(store.count(ROOM)).thenThrow(new RuntimeException("redis down")).thenReturn(0L, 1L, 5L);

        for (int i = 0; i < 4; i++) {
            tracker.tick();
        }

        assertThat(deliveredCounts(a)).containsExactly(1L, 5L);
    }

    @Test
    @DisplayName("[CHAT-PR-31] 아직 Redis 등록 전인 구독은 로컬 하한에서 제외된다 - 등록 대기 3명이 있어도 하한은 1 이라 집계 2 는 기존 구독자에게 나간다(입장 폭주 중 전달이 멈추지 않는다)")
    void unjoinedSubscriptionsAreExcludedFromFloor() {
        ChatSubscription a = join(ROOM, 1L, 1);
        ChatSubscription pending1 = registry.register(ROOM, 11L, false);
        ChatSubscription pending2 = registry.register(ROOM, 12L, false);
        ChatSubscription pending3 = registry.register(ROOM, 13L, false);
        when(store.count(ROOM)).thenReturn(2L);

        tracker.tick();

        assertThat(deliveredCounts(a)).containsExactly(1L, 2L);
        for (ChatSubscription p : List.of(pending1, pending2, pending3)) {
            assertThat(deliveredCounts(p)).as("등록 전 구독자에게는 주기 전달도 없다").isEmpty();
            assertThat(p.presenceSent()).isEqualTo(ChatSubscription.PRESENCE_NOT_JOINED);
        }
    }

    @Test
    @DisplayName("[CHAT-PR-15] presence 프레임은 이름 presence, id 없음, 데이터는 count 키 하나뿐이다")
    void frameShape() {
        SseFrame frame = SseFrame.presence(3);

        assertThat(frame.name()).isEqualTo("presence");
        assertThat(frame.id()).as("CHAT-PR-16: id 없음").isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) frame.data();
        assertThat(data).containsOnlyKeys("count").containsEntry("count", 3L);
    }
}
