package com.skhynix.quiz.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Field;
import java.util.Set;
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

    // ---------- participants(count) ----------

    @Test
    @DisplayName("[AC-CHAT-7-1] register()로 구독하면 해당 방의 participants(구독 수)가 1 증가한다")
    void register_incrementsParticipantsCount() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        assertThat(registry.count(ROOM_UID)).isZero();

        registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-CHAT-7-2] 같은 사용자가 두 탭에서 동시에 구독하면 participants가 2 증가한다(연결 기준 카운트)")
    void register_sameUserTwoTabs_countsBothConnections() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        registry.register(ROOM_UID, 1L);
        registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(2);
    }

    @Test
    @DisplayName("구독이 없는 방의 participants는 0이다(경계)")
    void count_roomWithNoSubscriptions_returnsZero() {
        SseEmitterRegistry registry = new SseEmitterRegistry();

        assertThat(registry.count("no-such-room")).isZero();
    }

    @Test
    @DisplayName("participants는 방별로 독립적으로 집계된다")
    void count_isPerRoom() {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        registry.register(ROOM_UID, 1L);

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(registry.count("other-room")).isZero();
    }

    // ---------- 연결 종료(퇴장) ----------

    @Test
    @DisplayName("[AC-CHAT-8-1] 구독 연결이 정상 완료(onCompletion)되면 participants가 1 감소한다")
    void onCompletion_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        assertThat(registry.count(ROOM_UID)).isEqualTo(1);

        triggerCompletion(emitter);

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("[AC-CHAT-8-1] 구독 연결이 타임아웃(onTimeout)되면 participants가 1 감소한다")
    void onTimeout_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        triggerTimeout(emitter);

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("[AC-CHAT-8-1] 구독 연결이 오류(onError)로 종료되면 participants가 1 감소한다")
    void onError_decrementsParticipantsCount() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        triggerError(emitter, new IllegalStateException("client disconnected"));

        assertThat(registry.count(ROOM_UID)).isZero();
    }

    @Test
    @DisplayName("[AC-CHAT-8-2 근사] 같은 연결의 종료 콜백이 중복 호출돼도 participants는 0 아래로 내려가지 않는다")
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
    @DisplayName("[AC-CHAT-11-4] 발신자가 멀티탭으로 구독 중이면 두 탭 모두 fan-out에서 제외된다")
    void publish_senderMultipleTabs_bothTabsExcluded() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter tab1 = registry.register(ROOM_UID, 1L);
        SseEmitter tab2 = registry.register(ROOM_UID, 1L);
        SseEmitter other = registry.register(ROOM_UID, 2L);

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", 1L));

        assertThat(earlySendCount(tab1)).isZero();
        assertThat(earlySendCount(tab2)).isZero();
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
    @DisplayName("publish 중 전송이 실패하는(이미 완료된) 구독은 즉시 회수돼 participants가 감소한다")
    void publish_deadConnection_isRemovedAndCountDecreases() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter alive = registry.register(ROOM_UID, 1L);
        SseEmitter dead = registry.register(ROOM_UID, 2L);
        dead.complete(); // 이후 send() 호출 시 IllegalStateException("already set complete")

        registry.publish(ROOM_UID, new RealtimeEvent("message", "payload", null));

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(earlySendCount(alive)).isGreaterThan(0);
    }

    // ---------- 하트비트(AC-CHAT-26) ----------

    @Test
    @DisplayName("[AC-CHAT-26-1] 유휴 연결에 heartbeat()를 실행하면 :ping 프레임 전송이 시도되고 구독은 유지된다")
    void heartbeat_liveConnection_sendsPingAndKeepsSubscription() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);

        registry.heartbeat();

        assertThat(registry.count(ROOM_UID)).isEqualTo(1);
        assertThat(earlySendCount(emitter)).isGreaterThan(0);
    }

    @Test
    @DisplayName("[AC-CHAT-26-2] leave 신호 없이 죽은 연결은 heartbeat() 전송 실패로 감지돼 회수되고 participants가 감소한다")
    void heartbeat_deadConnection_isRemovedAndParticipantsDecreases() throws Exception {
        SseEmitterRegistry registry = new SseEmitterRegistry();
        SseEmitter emitter = registry.register(ROOM_UID, 1L);
        emitter.complete(); // 클라이언트가 leave 신호 없이 끊긴 상황을 흉내

        registry.heartbeat();

        assertThat(registry.count(ROOM_UID)).isZero();
    }
}
