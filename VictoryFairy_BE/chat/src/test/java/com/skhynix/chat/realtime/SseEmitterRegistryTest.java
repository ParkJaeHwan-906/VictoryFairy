package com.skhynix.chat.realtime;

import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.support.SseCapture;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 파드 로컬 레지스트리. 서블릿 컨테이너 없이 실제 {@link SseFrameWriter} 와 함께 검증한다.
 * 연결 종료 콜백은 컨테이너만 부르므로 quiz 테스트처럼 리플렉션으로 직접 실행해 흉내 낸다.
 */
class SseEmitterRegistryTest {

    private static final String ROOM = "G1";

    private SseFrameWriter writer;
    private SseEmitterRegistry registry;

    @BeforeEach
    void setUp() {
        writer = new SseFrameWriter(props(150, 1000));
        writer.start();
        registry = new SseEmitterRegistry(writer);
    }

    @AfterEach
    void tearDown() {
        writer.stop();
    }

    @SuppressWarnings("unchecked")
    private static <T> T emitterField(SseEmitter emitter, String name) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(emitter);
    }

    private static void triggerCompletion(SseEmitter emitter) throws Exception {
        Runnable callback = emitterField(emitter, "completionCallback");
        callback.run();
    }

    private static void triggerTimeout(SseEmitter emitter) throws Exception {
        Runnable callback = emitterField(emitter, "timeoutCallback");
        callback.run();
    }

    private static void triggerError(SseEmitter emitter) throws Exception {
        Consumer<Throwable> callback = emitterField(emitter, "errorCallback");
        callback.accept(new IllegalStateException("client disconnected"));
    }

    private static boolean isClosed(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("probe"));
            return false;
        } catch (IllegalStateException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 등록·집계 ----------

    @Test
    @DisplayName("[CHAT-GC-30] register 하면 그 방의 구독 수가 1 늘고, 구독 수는 방별로 독립이다")
    void register_incrementsCountPerRoom() {
        assertThat(registry.count(ROOM)).isZero();

        registry.register(ROOM, 1L, false);

        assertThat(registry.count(ROOM)).isEqualTo(1);
        assertThat(registry.count("other")).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-33] 구독 emitter 타임아웃은 30분이다")
    void register_emitterTimeoutIsThirtyMinutes() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        assertThat(subscription.emitter().getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(SseEmitterRegistry.EMITTER_TIMEOUT_MS).isEqualTo(1_800_000L);
    }

    // ---------- last-one-wins (34) ----------

    @Test
    @DisplayName("[CHAT-GC-34] 같은 사용자가 같은 방에 재구독하면 기존 구독은 서버가 닫고 구독 수는 1→1 이다")
    void register_sameUserSameRoom_evictsOldSubscription() {
        ChatSubscription first = registry.register(ROOM, 1L, false);
        assertThat(registry.count(ROOM)).isEqualTo(1);

        ChatSubscription second = registry.register(ROOM, 1L, false);

        assertThat(registry.count(ROOM)).isEqualTo(1);
        assertThat(isClosed(first.emitter())).isTrue();
        assertThat(isClosed(second.emitter())).isFalse();
        assertThat(registry.subscriptions(ROOM)).containsExactly(second);
    }

    @Test
    @DisplayName("[CHAT-GC-34] 다른 방에 재구독해도 기존 구독을 방 불문 전부 닫는다(총 구독 수 1→1)")
    void register_sameUserDifferentRoom_evictsAcrossRooms() {
        ChatSubscription inRoomA = registry.register("A", 1L, false);

        ChatSubscription inRoomB = registry.register("B", 1L, false);

        assertThat(registry.count("A")).isZero();
        assertThat(registry.count("B")).isEqualTo(1);
        assertThat(isClosed(inRoomA.emitter())).isTrue();
        assertThat(isClosed(inRoomB.emitter())).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-34] 다른 사용자의 구독은 건드리지 않는다")
    void register_otherUsers_areUntouched() {
        ChatSubscription other = registry.register(ROOM, 2L, false);

        registry.register(ROOM, 1L, false);

        assertThat(registry.count(ROOM)).isEqualTo(2);
        assertThat(isClosed(other.emitter())).isFalse();
    }

    // ---------- 연결 종료 콜백 (33) ----------

    @Test
    @DisplayName("[CHAT-GC-33] 연결이 정상 완료(onCompletion)되면 레지스트리에서 빠진다")
    void onCompletion_removesSubscription() throws Exception {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        triggerCompletion(subscription.emitter());

        assertThat(registry.count(ROOM)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-33] 타임아웃(onTimeout)이면 emitter 를 complete 하고 레지스트리에서 제거한다")
    void onTimeout_completesAndRemoves() throws Exception {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        triggerTimeout(subscription.emitter());

        assertThat(registry.count(ROOM)).isZero();
        assertThat(isClosed(subscription.emitter())).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-33] 오류(onError)로 끝나면 레지스트리에서 빠진다")
    void onError_removesSubscription() throws Exception {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        triggerError(subscription.emitter());

        assertThat(registry.count(ROOM)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-33] 종료 콜백이 중복 호출돼도 구독 수는 0 아래로 내려가지 않고 다른 구독을 지우지 않는다")
    void completionTwice_isIdempotent() throws Exception {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        registry.register(ROOM, 2L, false);

        triggerCompletion(a.emitter());
        triggerCompletion(a.emitter());

        assertThat(registry.count(ROOM)).isEqualTo(1);
    }

    // ---------- 퇴장 (45~47) ----------

    @Test
    @DisplayName("[CHAT-GC-45] closeSubscriptions 는 그 사용자의 그 방 구독만 닫는다")
    void closeSubscriptions_closesOnlyThatUsersRoomSubscription() {
        ChatSubscription target = registry.register(ROOM, 1L, false);
        ChatSubscription neighbor = registry.register(ROOM, 2L, false);

        registry.closeSubscriptions(ROOM, 1L);

        assertThat(registry.count(ROOM)).isEqualTo(1);
        assertThat(isClosed(target.emitter())).isTrue();
        assertThat(isClosed(neighbor.emitter())).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-47] 종료할 구독이 없을 때 연속 호출해도 예외 없이 상태가 바뀌지 않는다")
    void closeSubscriptions_noSubscription_isIdempotentNoop() {
        assertThatCode(() -> registry.closeSubscriptions(ROOM, 1L)).doesNotThrowAnyException();
        assertThatCode(() -> registry.closeSubscriptions(ROOM, 1L)).doesNotThrowAnyException();
        assertThat(registry.count(ROOM)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장한 구독은 팬아웃 대상(subscriptions)에서 빠지고 이후 쓰기 시도가 없다")
    void closeSubscriptions_removedFromFanOutTargets() {
        ChatSubscription left = registry.register(ROOM, 1L, false);
        ChatSubscription other = registry.register(ROOM, 2L, false);

        registry.closeSubscriptions(ROOM, 1L);

        assertThat(registry.subscriptions(ROOM)).containsExactly(other);
        assertThat(registry.subscriptions(ROOM)).doesNotContain(left);
    }

    // ---------- 다중 파드 종료 명령 (35, 49, 92) ----------

    @Test
    @DisplayName("[CHAT-GC-35] 자기 인스턴스가 낸 축출 명령이 되돌아와도 방금 연 새 구독은 끊기지 않는다")
    void handleCloseCommand_ownOrigin_isIgnored() {
        ChatSubscription subscription = registry.register(ROOM, 1L, false);

        registry.handleCloseCommand(SubscriptionCloseCommand.evict(1L, registry.instanceId()));
        registry.handleCloseCommand(SubscriptionCloseCommand.leave(1L, registry.instanceId(), ROOM));

        assertThat(registry.count(ROOM)).isEqualTo(1);
        assertThat(isClosed(subscription.emitter())).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-49] 다른 인스턴스가 보낸 퇴장 명령은 그 방의 구독만 종료한다")
    void handleCloseCommand_remoteLeave_closesThatRoomOnly() {
        ChatSubscription inRoom = registry.register(ROOM, 1L, false);
        ChatSubscription neighbor = registry.register(ROOM, 2L, false);

        registry.handleCloseCommand(SubscriptionCloseCommand.leave(1L, "other-pod", ROOM));

        assertThat(registry.count(ROOM)).isEqualTo(1);
        assertThat(isClosed(inRoom.emitter())).isTrue();
        assertThat(isClosed(neighbor.emitter())).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-35] 다른 인스턴스가 보낸 축출(allRooms) 명령은 방 불문 그 사용자의 구독을 전부 종료한다")
    void handleCloseCommand_remoteEvict_closesAllRoomsOfUser() {
        ChatSubscription a = registry.register("A", 1L, false);
        // 같은 파드의 다른 방에 구독을 두려면 register 가 로컬 축출하므로 다른 사용자를 쓴다
        ChatSubscription b = registry.register("B", 2L, false);

        registry.handleCloseCommand(SubscriptionCloseCommand.evict(1L, "other-pod"));

        assertThat(isClosed(a.emitter())).isTrue();
        assertThat(isClosed(b.emitter())).isFalse();
        assertThat(registry.count("A")).isZero();
        assertThat(registry.count("B")).isEqualTo(1);
    }

    @Test
    @DisplayName("[CHAT-GC-92] 대상 사용자가 없거나(null) 퇴장 명령인데 gameId 가 없으면 아무것도 닫지 않는다")
    void handleCloseCommand_malformedCommands_areIgnored() {
        registry.register(ROOM, 1L, false);

        registry.handleCloseCommand(new SubscriptionCloseCommand("subscription-close", null, "x", true, null));
        registry.handleCloseCommand(new SubscriptionCloseCommand("subscription-close", 1L, "x", false, null));

        assertThat(registry.count(ROOM)).isEqualTo(1);
    }

    @Test
    @DisplayName("레지스트리 인스턴스마다 instanceId 가 다르다")
    void instanceId_isUniquePerRegistry() {
        SseEmitterRegistry other = new SseEmitterRegistry(writer);

        assertThat(registry.instanceId()).isNotBlank().isNotEqualTo(other.instanceId());
    }

    // ---------- 하트비트 (32, 36) ----------

    @Test
    @DisplayName("[CHAT-GC-32] heartbeat 는 모든 구독에 SSE 주석(:ping)을 쓰고 data 이벤트를 만들지 않는다")
    void heartbeat_writesPingCommentToEverySubscription() {
        ChatSubscription a = registry.register(ROOM, 1L, false);
        ChatSubscription b = registry.register("R2", 2L, false);

        registry.heartbeat();

        for (ChatSubscription subscription : new ChatSubscription[] {a, b}) {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(SseCapture.events(subscription.emitter())).anySatisfy(event -> {
                        assertThat(event.isComment()).isTrue();
                        assertThat(event.comment()).isEqualTo("ping");
                    }));
            assertThat(SseCapture.dataEvents(subscription.emitter())).isEmpty();
        }
        assertThat(registry.count(ROOM)).isEqualTo(1);
    }

    @Test
    @DisplayName("[CHAT-GC-36] 하트비트 쓰기가 실패한 죽은 연결은 레지스트리에서 회수되어 이후 팬아웃 대상에서 빠진다")
    void heartbeat_deadConnection_isReclaimed() {
        ChatSubscription alive = registry.register(ROOM, 1L, false);
        ChatSubscription dead = registry.register(ROOM, 2L, false);
        dead.emitter().complete(); // 클라이언트가 끊긴 뒤 컨테이너가 emitter 를 닫은 상황

        registry.heartbeat();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(registry.count(ROOM)).isEqualTo(1));
        assertThat(registry.subscriptions(ROOM)).containsExactly(alive);
    }

    // ---------- 고아 Set 레이스 회귀 (quiz AC-CHAT-11-5·55-2 이식) ----------

    /**
     * 마지막 구독 해제와 새 구독 등록이 겹치는 순간을 결정적으로 재현하는 {@link Set} 데코레이터(quiz 와 동일 기법).
     * "비었다"고 판정하는 바로 그 순간 멈춰 서서 다른 스레드의 register() 가 끼어들 시간을 벌어 준다.
     * compute/computeIfPresent 의 빈(bin) 잠금이 이 틈을 막으면, 대기가 타임아웃으로 풀려도 끼어들 수 없어 고아가 생기지 않는다.
     */
    private static final class OrphanRaceSet extends AbstractSet<Object> {
        private final Set<Object> delegate;
        private final CountDownLatch emptyObserved;
        private final CountDownLatch registrationLanded;
        private final long awaitTimeoutMillis;
        private volatile boolean armed;

        OrphanRaceSet(Set<Object> delegate, CountDownLatch emptyObserved, CountDownLatch registrationLanded,
                long awaitTimeoutMillis) {
            this.delegate = delegate;
            this.emptyObserved = emptyObserved;
            this.registrationLanded = registrationLanded;
            this.awaitTimeoutMillis = awaitTimeoutMillis;
        }

        void arm() {
            this.armed = true;
        }

        @Override
        public Iterator<Object> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean add(Object o) {
            return delegate.add(o);
        }

        @Override
        public boolean remove(Object o) {
            return delegate.remove(o);
        }

        @Override
        public boolean isEmpty() {
            boolean empty = delegate.isEmpty();
            if (armed && empty) {
                emptyObserved.countDown();
                try {
                    registrationLanded.await(awaitTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return empty;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<Object>> roomsMap(SseEmitterRegistry registry) throws Exception {
        Field field = SseEmitterRegistry.class.getDeclaredField("rooms");
        field.setAccessible(true);
        return (Map<String, Set<Object>>) field.get(registry);
    }

    /** 방에 A(userId=1) 하나만 있는 상태에서 A 의 마지막 구독 해제와 C(userId=2)의 등록을 겹치게 만든다. 반환은 C 의 구독. */
    private ChatSubscription reproduceOrphanRace() throws Exception {
        ChatSubscription subscriptionA = registry.register(ROOM, 1L, false);

        Map<String, Set<Object>> rooms = roomsMap(registry);
        Set<Object> backing = ConcurrentHashMap.newKeySet();
        backing.addAll(rooms.get(ROOM));
        CountDownLatch emptyObserved = new CountDownLatch(1);
        CountDownLatch registrationLanded = new CountDownLatch(1);
        OrphanRaceSet racySet = new OrphanRaceSet(backing, emptyObserved, registrationLanded, 500L);
        rooms.put(ROOM, racySet);
        racySet.arm();

        Thread leaveThread = new Thread(() -> {
            try {
                triggerCompletion(subscriptionA.emitter()); // remove(ROOM, A) 트리거
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "orphan-race-leave");
        leaveThread.start();

        if (!emptyObserved.await(2, TimeUnit.SECONDS)) {
            leaveThread.join(1000);
            throw new IllegalStateException("퇴장 스레드가 '빈 Set' 판정 지점에 도달하지 못했다(테스트 설계 오류 가능성)");
        }

        ChatSubscription landed = registry.register(ROOM, 2L, false);
        registrationLanded.countDown();

        leaveThread.join(2000);
        if (leaveThread.isAlive()) {
            throw new IllegalStateException("퇴장 스레드가 시간 내에 끝나지 않았다(테스트 설계 오류 가능성)");
        }
        return landed;
    }

    @Test
    @DisplayName("[CHAT-GC-36 회귀: quiz AC-CHAT-11-5] 마지막 구독 해제와 새 구독 등록이 동시에 일어나도 등록된 구독은 팬아웃 대상(subscriptions)에서 빠지지 않는다")
    void concurrentUnsubscribeAndRegister_subscriptionStillInFanOutTargets() throws Exception {
        ChatSubscription landed = reproduceOrphanRace();

        assertThat(registry.count(ROOM)).as("새 구독이 고아 Set 이 아니라 살아 있는 맵 엔트리에 남아 있어야 한다").isEqualTo(1);
        assertThat(registry.subscriptions(ROOM)).as("팬아웃(배처)이 순회하는 Set 에 들어 있어야 한다").contains(landed);
    }

    @Test
    @DisplayName("[CHAT-GC-36 회귀: quiz AC-CHAT-55-2] 마지막 구독 해제와 새 구독 등록이 동시에 일어나도 등록된 구독은 heartbeat 순회에서 빠지지 않는다")
    void concurrentUnsubscribeAndRegister_heartbeatStillReachesNewSubscription() throws Exception {
        ChatSubscription landed = reproduceOrphanRace();

        registry.heartbeat();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(SseCapture.events(landed.emitter())).anySatisfy(event ->
                        assertThat(event.comment()).isEqualTo("ping")));
        assertThat(registry.count(ROOM)).isEqualTo(1);
    }

    // ---------- 종료 (graceful) ----------

    @Test
    @DisplayName("[CHAT-GC-33] 컨텍스트 종료(stop) 시 모든 방의 구독을 닫고 레지스트리를 비운다(열린 SSE 가 종료를 붙잡지 않는다)")
    void stop_closesAllSubscriptionsAndEmptiesRegistry() {
        registry.start();
        ChatSubscription a = registry.register("A", 1L, false);
        ChatSubscription b = registry.register("B", 2L, false);

        registry.stop();

        assertThat(registry.isRunning()).isFalse();
        assertThat(registry.count("A")).isZero();
        assertThat(registry.count("B")).isZero();
        assertThat(isClosed(a.emitter())).isTrue();
        assertThat(isClosed(b.emitter())).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-33] 정지 순서: 컨슈머 → 배처 → 쓰기 풀 → 레지스트리 이고 모두 웹 서버 graceful shutdown 단계보다 높은 phase 다")
    void lifecyclePhases_stopBeforeWebServerGracefulShutdown() {
        int webServerGracefulPhase = org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 1024;

        assertThat(SseEmitterRegistry.PHASE).isLessThan(SseFrameWriter.PHASE);
        assertThat(SseFrameWriter.PHASE).isLessThan(com.skhynix.chat.gateway.service.RoomBatcher.PHASE);
        assertThat(com.skhynix.chat.gateway.service.RoomBatcher.PHASE)
                .isLessThan(com.skhynix.chat.gateway.service.GatewayConsumer.PHASE);
        assertThat(SseEmitterRegistry.PHASE).isGreaterThan(webServerGracefulPhase);
    }

    @Test
    @DisplayName("구독자가 없는 방의 subscriptions 는 빈 집합이고 예외 없이 순회된다")
    void subscriptions_unknownRoom_isEmpty() {
        assertThat(registry.subscriptions("none")).isEmpty();
        assertThatThrownBy(() -> registry.subscriptions("none").add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
