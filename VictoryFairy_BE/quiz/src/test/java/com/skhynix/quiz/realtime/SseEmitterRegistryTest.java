package com.skhynix.quiz.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SseEmitterRegistry}를 실제 서블릿 컨테이너 없이 순수 단위로 검증한다.
 *
 * <p><b>테스트 기법 설명(리플렉션을 쓰는 이유)</b>: {@link SseEmitter#send}는 서블릿 비동기 핸들러가
 * 아직 초기화되지 않은 상태({@code handler == null}, 순수 단위 테스트에서는 항상 이 상태)에서는 예외를
 * 던지지 않고 {@code ResponseBodyEmitter}의 private 필드 {@code earlySendAttempts}에 조용히 쌓인다.
 * 이 필드를 리플렉션으로 읽어 "이 emitter로 실제 전송 시도가 있었는지"를 관찰해, 발신자 제외 fan-out
 * (AC-CHAT-11-2/11-4)을 화이트박스로 검증한다. 마찬가지로 {@code onCompletion/onTimeout/onError}에 등록한
 * 콜백은 실제 서블릿 컨테이너가 연결 종료 시점에만 호출하므로, {@code completionCallback}/
 * {@code timeoutCallback}/{@code errorCallback} 필드(모두 공개 인터페이스 {@link Runnable}/
 * {@link Consumer}로 캐스팅 가능)를 리플렉션으로 꺼내 직접 실행시켜 연결 종료를 흉내낸다. 프로덕션
 * 코드는 건드리지 않고, Spring 프레임워크 자체의 안정적인 필드 이름에만 의존한다.
 *
 * <p>반대로 "죽은 연결"은 리플렉션 없이도 순수 공개 API로 흉내낼 수 있다: 이미 {@link SseEmitter#complete()}가
 * 호출된 emitter에 {@code send()}를 시도하면 {@code ResponseBodyEmitter}가
 * {@code IllegalStateException}("already set complete")을 던지므로, 레지스트리의
 * {@code catch (Exception e) { remove(...); }} 경로를 정확히 재현한다.
 */
class SseEmitterRegistryTest {

    private static final String ROOM_UID = "room-1";

    @SuppressWarnings("unchecked")
    private static <T> T getEmitterField(SseEmitter emitter, String fieldName) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(emitter);
    }

    private static int earlySendCount(SseEmitter emitter) throws Exception {
        Set<?> earlySendAttempts = getEmitterField(emitter, "earlySendAttempts");
        return earlySendAttempts.size();
    }

    private static void triggerCompletion(SseEmitter emitter) throws Exception {
        Runnable callback = getEmitterField(emitter, "completionCallback");
        callback.run();
    }

    private static void triggerTimeout(SseEmitter emitter) throws Exception {
        Runnable callback = getEmitterField(emitter, "timeoutCallback");
        callback.run();
    }

    private static void triggerError(SseEmitter emitter, Throwable error) throws Exception {
        Consumer<Throwable> callback = getEmitterField(emitter, "errorCallback");
        callback.accept(error);
    }

    // ---------- 구독 수(count) ----------

    @Test
    @DisplayName("register()로 구독하면 레지스트리 내부 구독 수(count)가 1 증가한다(참여 인원 미노출과는 무관한 내부 집계 — QUIZ-CHAT-7 철회, 대응 인수 시나리오 없음)")
    void register_incrementsParticipantsCount() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        assertThat(registry.count(ROOM_UID)).isZero();

        registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-24] 같은 사용자가 재구독하면(last-one-wins) 기존 구독이 축출(complete)되고 "
            + "구독 수는 1→1로 유지된다(2026-08-04 계약 변경 — 과거엔 멀티탭 2연결을 허용했으나 이제 "
            + "새 구독이 기존 구독을 끊는다. 옛 계약을 검증하던 테스트를 대체함)")
    void register_sameUserRegistersAgain_evictsExistingSubscriptionAndCountStaysOne() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        SseEmitter first = registry.register(ROOM_UID, 1L);
        assertThat(registry.count(ROOM_UID)).isEqualTo(1);

        SseEmitter second = registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        // 축출된 first는 이미 complete() 처리됐으므로 send()가 "already set complete" 예외를 던진다
        // (publish_deadConnection_isRemovedAndCountDecreases 테스트와 동일한 공개 API 관찰 기법).
        assertThatThrownBy(() -> first.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
        // second는 살아 있어 send 시도 자체는 예외 없이 접수된다(unit 테스트에서는 실제 전송 없이 큐잉만 됨).
        assertThatCode(() -> second.send(SseEmitter.event().comment("still-alive")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("구독이 없는 방의 구독 수는 0이다(경계)")
    void count_roomWithNoSubscriptions_returnsZero() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        assertThat(registry.count("no-such-room")).isZero();
    }

    @Test
    @DisplayName("구독 수는 방별로 독립적으로 집계된다")
    void count_isPerRoom() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(registry.count("other-room")).isZero();
    }

    // ---------- 연결 종료(퇴장) ----------

    @Test
    @DisplayName("구독 연결이 정상 완료(onCompletion)되면 내부 구독 수가 1 감소한다(QUIZ-CHAT-8 철회, 대응 인수 시나리오 없음)")
    void onCompletion_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        assertThat(registry.count(ROOM_UID)).isEqualTo(1);

        triggerCompletion(emitter);

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("구독 연결이 타임아웃(onTimeout)되면 내부 구독 수가 1 감소한다(QUIZ-CHAT-8 철회, 대응 인수 시나리오 없음)")
    void onTimeout_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        triggerTimeout(emitter);

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("구독 연결이 오류(onError)로 종료되면 내부 구독 수가 1 감소한다(QUIZ-CHAT-8 철회, 대응 인수 시나리오 없음)")
    void onError_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        triggerError(emitter, new IllegalStateException("client disconnected"));

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("같은 연결의 종료 콜백이 중복 호출돼도 내부 구독 수는 0 아래로 내려가지 않는다(QUIZ-CHAT-8 철회, 대응 인수 시나리오 없음)")
    void onCompletion_calledTwice_doesNotUnderflowBelowZero() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        triggerCompletion(emitter);
        triggerCompletion(emitter);

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    // ---------- 발신자 제외 fan-out ----------

    @Test
    @DisplayName("[AC-CHAT-11-2] publish()는 발신자(excludeUserAccountId)의 구독에는 전송을 시도하지 않는다")
    void publish_excludesSenderSubscription() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter senderEmitter = registry.register(ROOM_UID, 1L); // 발신자 A
        SseEmitter otherEmitter = registry.register(ROOM_UID, 2L);  // 구독자 B

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", 1L));

        assertThat(earlySendCount(senderEmitter)).isZero();
        assertThat(earlySendCount(otherEmitter)).isGreaterThan(0);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-24/25] 재구독으로 축출된 이전 탭은 이후 발행 대상에서 완전히 빠지고, "
            + "살아남은 새 탭과 다른 사용자에게는 정상 도달한다(2026-08-04 계약 변경 — "
            + "과거엔 같은 사용자의 멀티탭 동시 존재를 전제로 '발신자 두 탭 모두 제외'를 검증했으나, "
            + "last-one-wins 축출로 그 전제 자체가 성립하지 않아 새 계약으로 대체함)")
    void publish_afterEviction_reachesSurvivingConnectionOnlyExcludingEvictedTab() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter oldTab = registry.register(ROOM_UID, 1L);
        SseEmitter newTab = registry.register(ROOM_UID, 1L); // 재구독 — oldTab 축출(QUIZ-CTAC-24)
        SseEmitter other = registry.register(ROOM_UID, 2L);

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(earlySendCount(oldTab))
                .as("축출된 연결은 레지스트리에서 완전히 떨어져 나가 전송 시도 자체가 없어야 한다")
                .isZero();
        assertThat(earlySendCount(newTab)).isGreaterThan(0);
        assertThat(earlySendCount(other)).isGreaterThan(0);
    }

    @Test
    @DisplayName("[AC-CHAT-11-3] 구독자가 없는 방에 publish해도 예외 없이 무시된다")
    void publish_roomWithNoSubscribers_doesNotThrow() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        assertThatCode(() -> registry.publish("empty-room", new RealtimeEvent("message", "payload", null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("제외 대상이 없으면(exclude=null) 방의 모든 구독자에게 전송을 시도한다")
    void publish_withoutExclusion_sendsToAllSubscribers() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitterA = registry.register(ROOM_UID, 1L);
        SseEmitter emitterB = registry.register(ROOM_UID, 2L);

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(earlySendCount(emitterA)).isGreaterThan(0);
        assertThat(earlySendCount(emitterB)).isGreaterThan(0);
    }

    @Test
    @DisplayName("publish 중 전송이 실패하는(이미 완료된) 구독은 즉시 회수돼 구독 수가 감소한다")
    void publish_deadConnection_isRemovedAndCountDecreases() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter alive = registry.register(ROOM_UID, 1L);
        SseEmitter dead = registry.register(ROOM_UID, 2L);
        dead.complete(); // 이후 send() 호출 시 IllegalStateException("already set complete")

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(earlySendCount(alive)).isGreaterThan(0);
    }

    // ---------- 하트비트·죽은 연결 회수(AC-CHAT-55·56, QUIZ-CHAT-26에서 승계) ----------

    @Test
    @DisplayName("[AC-CHAT-55-1] 유휴 연결에 heartbeat()를 실행하면 :ping 프레임 전송이 시도되고 구독은 유지된다")
    void heartbeat_liveConnection_sendsPingAndKeepsSubscription() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        registry.heartbeat();

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(earlySendCount(emitter)).isGreaterThan(0);
    }

    @Test
    @DisplayName("[AC-CHAT-56-1] leave 신호 없이 죽은 연결은 heartbeat() 전송 실패로 감지돼 회수되고 구독 수가 감소한다")
    void heartbeat_deadConnection_isRemovedAndParticipantsDecreases() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        emitter.complete(); // 클라이언트가 leave 신호 없이 끊긴 상황을 흉내

        registry.heartbeat();

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    // ---------- 명시적 퇴장(F절, QUIZ-CTAC-19/20/25) ----------

    @Test
    @DisplayName("[QUIZ-CTAC-19] closeSubscriptions()는 지정 사용자의 그 방 구독을 종료해 "
            + "구독 수가 1 감소하고 emitter가 complete된다")
    void closeSubscriptions_activeSubscription_decrementsCountAndCompletesEmitter() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        assertThat(registry.count(ROOM_UID)).isEqualTo(1);

        registry.closeSubscriptions(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isZero();
        assertThatThrownBy(() -> emitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-20] 끊을 구독이 없어도 closeSubscriptions()는 예외 없이 멱등하게 동작하고, "
            + "연속 호출해도 상태가 바뀌지 않는다")
    void closeSubscriptions_noExistingSubscription_isIdempotentNoop() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        assertThatCode(() -> registry.closeSubscriptions(ROOM_UID, 1L)).doesNotThrowAnyException();
        assertThatCode(() -> registry.closeSubscriptions(ROOM_UID, 1L)).doesNotThrowAnyException();
        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("[QUIZ-CTAC-25] 퇴장(closeSubscriptions)한 연결은 이후 메시지 fan-out 대상에서 제외되고 "
            + "다른 연결에는 정상 도달한다")
    void publish_afterCloseSubscriptions_excludesLeftConnection() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter left = registry.register(ROOM_UID, 1L);
        SseEmitter other = registry.register(ROOM_UID, 2L);
        registry.closeSubscriptions(ROOM_UID, 1L);

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(earlySendCount(left)).isZero();
        assertThat(earlySendCount(other)).isGreaterThan(0);
    }

    // ---------- 다중 파드 전파(handleCloseCommand, QUIZ-CTAC-28/29) ----------

    @Test
    @DisplayName("instanceId()는 null이 아니며 레지스트리 인스턴스마다 서로 다른 값이다")
    void instanceId_isNonNullAndUniquePerRegistry() {
        SseEmitterRegistry a = new SseEmitterRegistry();
        SseEmitterRegistry b = new SseEmitterRegistry();

        assertThat(a.instanceId()).isNotNull();
        assertThat(a.instanceId()).isNotEqualTo(b.instanceId());
    }

    @Test
    @DisplayName("[QUIZ-CTAC-28/29] handleCloseCommand()는 자신이 낸(originInstanceId가 자기 instanceId와 같은) "
            + "명령은 무시한다 — 그렇지 않으면 발행 파드가 방금 연 새 구독이 자기 축출 명령에 끊긴다")
    void handleCloseCommand_ownInstanceOrigin_isIgnored() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        registry.register(ROOM_UID, 1L);
        SubscriptionCloseCommand selfCommand = SubscriptionCloseCommand.leave(1L, registry.instanceId());

        registry.handleCloseCommand(ROOM_UID, selfCommand);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-28] handleCloseCommand()는 다른 인스턴스가 보낸 leave 명령이면 "
            + "그 토픽 방의 구독만 종료한다(퇴장 요청을 받은 인스턴스가 로컬에 없어도 구독을 들고 있는 "
            + "인스턴스에서는 실제로 종료돼야 함)")
    void handleCloseCommand_otherInstanceLeaveCommand_closesRoomSubscription() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        SubscriptionCloseCommand remoteLeave = SubscriptionCloseCommand.leave(1L, "other-instance-id");

        registry.handleCloseCommand(ROOM_UID, remoteLeave);

        assertThat(registry.count(ROOM_UID)).isZero();
        assertThatThrownBy(() -> emitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[QUIZ-CTAC-29] handleCloseCommand()는 다른 인스턴스가 보낸 evict(allRooms) 명령이면 "
            + "토픽으로 들어온 방과 무관하게 그 사용자의 구독을 방을 가리지 않고 전부 종료한다"
            + "(두 파드에 동시에 살아 있는 연결이 남지 않아야 함)")
    void handleCloseCommand_otherInstanceEvictCommand_closesAllRoomsForUser() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter roomAEmitter = registry.register("room-a", 1L);
        SseEmitter roomBEmitter = registry.register("room-b", 1L);
        SubscriptionCloseCommand remoteEvict = SubscriptionCloseCommand.evict(1L, "other-instance-id");

        // 토픽은 room-a로 들어왔지만 allRooms=true라 room-b 구독까지 함께 종료된다.
        registry.handleCloseCommand("room-a", remoteEvict);

        assertThat(registry.count("room-a")).isZero();
        assertThat(registry.count("room-b")).isZero();
        assertThatThrownBy(() -> roomAEmitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> roomBEmitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 고아 Set 레이스 회귀 (2026-08-01, chat.md 제약 절 9 / AC-CHAT-11-5·55-2) ----------

    /**
     * 방 R의 "마지막 구독 해제"와 "새 구독 등록"이 겹치는 순간을 결정적으로(스레드 스케줄링에 기대지
     * 않고) 재현하기 위한 테스트 전용 {@link Set} 데코레이터.
     *
     * <p>수정 전 {@code remove()}는 {@code rooms.get(key)}로 Set을 얻은 뒤 그 Set의
     * {@code isEmpty()}가 참이면 {@code rooms.remove(key)}(맵에서 통째로 걷어냄)를 호출했다. 이 둘
     * 사이의 틈이 바로 고아 Set 레이스의 창구다: 그 틈에 {@code register()}가 같은(아직 맵에 남아
     * 있는) Set에 새 구독을 얹으면, 뒤이은 {@code rooms.remove(key)}가 그 Set 전체를 맵에서
     * 떼어내 버려 새 구독이 고아가 된다.
     *
     * <p>이 데코레이터는 실제 {@code rooms} 맵의 값(Set)을 {@link SseEmitterRegistry}의 private
     * 필드에 리플렉션으로 주입해 교체한 뒤, 그 Set의 {@code isEmpty()}가 "비었다"고 판정하는
     * 바로 그 순간(=낡은 코드가 {@code rooms.remove(key)}를 부르기 직전)에 멈춰 서서 다른 스레드의
     * {@code register()}가 끼어들 시간을 강제로 벌어준다. 프로덕션 코드는 전혀 건드리지 않는다.
     *
     * <p>수정된 코드({@code compute}/{@code computeIfPresent})에서는 같은 키에 대한 이 두 호출이
     * {@link ConcurrentHashMap}의 빈(bin) 잠금 아래 상호 배타적이라, {@code register()} 쪽이 이
     * 대기 구간 동안 아예 진입하지 못하고 블록된다 — 그래서 대기가 타임아웃으로 풀려도 끼어들 틈이
     * 없었으므로 고아가 생기지 않는다. 즉 이 테스트는 "수정 전엔 반드시 재현되고, 수정 후엔 락 자체가
     * 재현을 원천 차단한다"는 사실에 기대는 결정적(=확률적이지 않은) 회귀 테스트다.
     */
    private static final class OrphanRaceSet extends AbstractSet<Object> {
        private final Set<Object> delegate;
        private final CountDownLatch emptyObserved;
        private final CountDownLatch registrationLanded;
        private final long awaitTimeoutMillis;
        private volatile boolean armed;

        OrphanRaceSet(Set<Object> delegate, CountDownLatch emptyObserved,
                CountDownLatch registrationLanded, long awaitTimeoutMillis) {
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
    private static Map<String, Set<Object>> getRoomsMap(SseEmitterRegistry registry) throws Exception {
        Field field = SseEmitterRegistry.class.getDeclaredField("rooms");
        field.setAccessible(true);
        return (Map<String, Set<Object>>) field.get(registry);
    }

    /**
     * 방 R에 구독자 A(userId=1) 하나만 있는 상태에서 A의 퇴장(마지막 구독 해제)과 새 구독자
     * C(userId=2)의 등록을 결정적으로 겹치게 만든다. 반환된 emitter가 C의 구독이다.
     */
    private static SseEmitter reproduceOrphanRace(SseEmitterRegistry registry) throws Exception {
        SseEmitter emitterA = registry.register(ROOM_UID, 1L);

        Map<String, Set<Object>> rooms = getRoomsMap(registry);
        Set<Object> original = rooms.get(ROOM_UID);
        Set<Object> backing = ConcurrentHashMap.newKeySet();
        backing.addAll(original);
        CountDownLatch emptyObserved = new CountDownLatch(1);
        CountDownLatch registrationLanded = new CountDownLatch(1);
        OrphanRaceSet racySet = new OrphanRaceSet(backing, emptyObserved, registrationLanded, 500L);
        rooms.put(ROOM_UID, racySet);
        racySet.arm();

        Thread leaveThread = new Thread(() -> {
            try {
                triggerCompletion(emitterA); // remove(ROOM_UID, subscriptionA) 트리거
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "orphan-race-leave");
        leaveThread.start();

        boolean reachedBlock = emptyObserved.await(2, TimeUnit.SECONDS);
        if (!reachedBlock) {
            leaveThread.join(1000);
            throw new IllegalStateException("퇴장 스레드가 '빈 Set' 판정 지점에 도달하지 못했다(테스트 설계 오류 가능성)");
        }

        // 퇴장 스레드가 "이 Set은 비었다"고 이미 판정한(그러나 맵 정리는 아직 하지 않은) 바로 그
        // 순간에 새 구독을 등록한다. 수정 전 코드라면 이 등록이 곧 떨어져 나갈 그 Set에 실린다.
        SseEmitter newEmitter = registry.register(ROOM_UID, 2L);
        registrationLanded.countDown();

        leaveThread.join(2000);
        if (leaveThread.isAlive()) {
            throw new IllegalStateException("퇴장 스레드가 시간 내에 끝나지 않았다(테스트 설계 오류 가능성)");
        }

        return newEmitter;
    }

    @Test
    @DisplayName("[AC-CHAT-11-5] 마지막 구독 해제와 새 구독 등록이 동시에 일어나도, 등록된 구독은 publish() 전달 대상에서 빠지지 않는다")
    void concurrentUnsubscribeAndRegister_publishStillReachesNewSubscription() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        SseEmitter newEmitter = reproduceOrphanRace(registry);
        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(registry.count(ROOM_UID))
                .as("새 구독이 고아 Set이 아니라 살아 있는 맵 엔트리에 남아 있어야 한다")
                .isEqualTo(1);
        assertThat(earlySendCount(newEmitter))
                .as("고아 Set에 갇혀 fan-out에서 조용히 누락되지 않아야 한다")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("[AC-CHAT-55-2] 마지막 구독 해제와 새 구독 등록이 동시에 일어나도, 등록된 구독은 heartbeat() 대상에서 빠지지 않는다")
    void concurrentUnsubscribeAndRegister_heartbeatStillReachesNewSubscription() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        SseEmitter newEmitter = reproduceOrphanRace(registry);
        registry.heartbeat();

        assertThat(registry.count(ROOM_UID))
                .as("새 구독이 고아 Set이 아니라 살아 있는 맵 엔트리에 남아 있어야 한다")
                .isEqualTo(1);
        assertThat(earlySendCount(newEmitter))
                .as("고아 Set에 갇혀 heartbeat 순회에서 조용히 누락되지 않아야 한다")
                .isGreaterThan(0);
    }
}
