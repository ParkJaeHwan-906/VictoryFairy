package com.skhynix.quiz.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.skhynix.quiz.realtime.QuizRoomSseRegistry.PresenceListener;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry.Subscription;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 퀴즈방 SSE 레지스트리 순수 단위 검증. 핵심은 채팅 레지스트리({@link SseEmitterRegistry})와 맵을 공유하지 않아서
 * 한쪽 register() 의 last-one-wins 축출이 다른 쪽에 닿지 않는다는 것(QUIZ-CQR-91).
 *
 * <p>죽은 연결 판별: 이미 complete() 된 emitter 에 send() 하면 IllegalStateException 이 난다.
 * 전송 시도 관찰: 서블릿 핸들러가 없는 단위 환경에서는 send() 가 {@code earlySendAttempts} 에 쌓이므로 리플렉션으로 센다.
 */
class QuizRoomSseRegistryTest {

    private static final Long USER = 7L;
    private static final Long OTHER = 8L;

    private QuizRoomSseRegistry registry;
    private final RecordingListener listener = new RecordingListener();

    @BeforeEach
    void setUp() {
        registry = new QuizRoomSseRegistry();
        registry.setPresenceListener(listener);
    }

    private static boolean isClosed(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("probe"));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static int earlySendCount(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        return ((Set<?>) field.get(emitter)).size();
    }

    private static void triggerCompletion(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("completionCallback");
        field.setAccessible(true);
        ((Runnable) field.get(emitter)).run();
    }

    private static final class RecordingListener implements PresenceListener {
        final List<Subscription> left = new ArrayList<>();
        int heartbeats;

        @Override
        public void left(Subscription subscription) {
            left.add(subscription);
        }

        @Override
        public void heartbeat() {
            heartbeats++;
        }
    }

    // ---- 토픽 ----

    @Test
    @DisplayName("퀴즈방 토픽은 quiz-room:{roomId} 이고, 채팅 방 uid(UUID)·null 은 퀴즈방 토픽이 아니다")
    void topic_formatAndDiscrimination() {
        assertThat(QuizRoomSseRegistry.topic(5L)).isEqualTo("quiz-room:5");
        assertThat(QuizRoomSseRegistry.isTopic("quiz-room:5")).isTrue();
        assertThat(QuizRoomSseRegistry.isTopic("0b0f6e3c-9d2c-4a54-8f3b-3c1c4a8a6d11")).isFalse();
        assertThat(QuizRoomSseRegistry.isTopic(null)).isFalse();
    }

    // ---- 등록과 축출 ----

    @Test
    @DisplayName("[QUIZ-CQR-80] 등록하면 그 방 구독 집합에 들어가고 계정·방 id 를 기억한다")
    void register_addsToRoom() {
        Subscription subscription = registry.register(1L, USER);

        assertThat(registry.subscriptions(1L)).containsExactly(subscription);
        assertThat(subscription.roomId()).isEqualTo(1L);
        assertThat(subscription.userAccountId()).isEqualTo(USER);
        assertThat(subscription.presenceSent()).isEqualTo(QuizRoomSseRegistry.PRESENCE_NOT_JOINED);
        assertThat(registry.roomIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 같은 계정이 방 2 를 새로 구독하면 방 1 구독은 끝나고 인원 집계에 퇴장이 통보된다")
    void register_otherRoom_evictsPreviousRoomSubscription() {
        Subscription room1 = registry.register(1L, USER);

        Subscription room2 = registry.register(2L, USER);

        assertThat(registry.subscriptions(1L)).isEmpty();
        assertThat(registry.roomIds()).containsExactly(2L);
        assertThat(isClosed(room1.emitter())).isTrue();
        assertThat(isClosed(room2.emitter())).isFalse();
        assertThat(listener.left).containsExactly(room1);
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 같은 방을 같은 계정이 다시 구독해도 이전 구독만 끝나고 새 구독은 남는다 (탭 하나만 유지)")
    void register_sameRoomAgain_replacesOldSubscription() {
        Subscription first = registry.register(1L, USER);

        Subscription second = registry.register(1L, USER);

        assertThat(registry.subscriptions(1L)).containsExactly(second);
        assertThat(isClosed(first.emitter())).isTrue();
        assertThat(isClosed(second.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 다른 계정의 구독은 축출되지 않는다")
    void register_doesNotEvictOtherAccounts() {
        Subscription other = registry.register(1L, OTHER);

        registry.register(2L, USER);

        assertThat(registry.subscriptions(1L)).containsExactly(other);
        assertThat(isClosed(other.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-91] 퀴즈방 register 는 같은 계정의 채팅 레지스트리 구독을 끊지 않는다")
    void quizRoomRegister_doesNotEvictChatSubscription() {
        SseEmitterRegistry chat = new SseEmitterRegistry();
        SseEmitter chatEmitter = chat.register("chat-room-1", USER);

        Subscription quiz = registry.register(1L, USER);

        assertThat(chat.count("chat-room-1")).isEqualTo(1);
        assertThat(isClosed(chatEmitter)).isFalse();
        assertThat(registry.subscriptions(1L)).containsExactly(quiz);
    }

    @Test
    @DisplayName("[QUIZ-CQR-91] 채팅 레지스트리 register 도 같은 계정의 퀴즈방 구독을 끊지 않는다")
    void chatRegister_doesNotEvictQuizRoomSubscription() {
        Subscription quiz = registry.register(1L, USER);

        new SseEmitterRegistry().register("chat-room-1", USER);

        assertThat(registry.subscriptions(1L)).containsExactly(quiz);
        assertThat(isClosed(quiz.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-91] 채팅 레지스트리의 전면 축출(closeAllSubscriptions)·종료 명령도 퀴즈방 구독에 닿지 않는다")
    void chatCloseAll_doesNotTouchQuizRoomSubscription() {
        Subscription quiz = registry.register(1L, USER);
        SseEmitterRegistry chat = new SseEmitterRegistry();
        chat.register("chat-room-1", USER);

        chat.closeAllSubscriptions(USER);
        chat.handleCloseCommand("chat-room-1", SubscriptionCloseCommand.evict(USER, "other-pod"));

        assertThat(registry.subscriptions(1L)).containsExactly(quiz);
        assertThat(isClosed(quiz.emitter())).isFalse();
    }

    // ---- 퇴장 ----

    @Test
    @DisplayName("[QUIZ-CQR-85] closeSubscriptions 는 그 방의 그 계정 구독만 끝낸다 (다른 방·다른 계정은 그대로)")
    void closeSubscriptions_onlyThatRoomAndAccount() {
        Subscription mine = registry.register(1L, USER);
        Subscription others = registry.register(1L, OTHER);

        registry.closeSubscriptions(1L, USER);

        assertThat(registry.subscriptions(1L)).containsExactly(others);
        assertThat(isClosed(mine.emitter())).isTrue();
        assertThat(isClosed(others.emitter())).isFalse();
        assertThat(listener.left).containsExactly(mine);
    }

    @Test
    @DisplayName("[QUIZ-CQR-87] 구독이 없는 방·계정에 대한 closeSubscriptions 는 아무 일도 하지 않고 예외도 없다")
    void closeSubscriptions_nothingToClose_isNoOp() {
        assertThatCode(() -> registry.closeSubscriptions(999999L, USER)).doesNotThrowAnyException();

        assertThat(listener.left).isEmpty();
        assertThat(registry.roomIds()).isEmpty();
    }

    @Test
    @DisplayName("방의 마지막 구독이 빠지면 방 키 자체가 걷힌다")
    void lastSubscriptionRemoved_roomKeyDropped() {
        registry.register(1L, USER);

        registry.closeSubscriptions(1L, USER);

        assertThat(registry.roomIds()).isEmpty();
    }

    @Test
    @DisplayName("[QUIZ-CQR-86] 컨테이너가 연결 종료를 알리면(onCompletion) 구독이 맵에서 빠지고 인원 집계에 퇴장이 한 번만 통보된다")
    void completionCallback_removesAndNotifiesOnce() throws Exception {
        Subscription subscription = registry.register(1L, USER);

        triggerCompletion(subscription.emitter());
        triggerCompletion(subscription.emitter());

        assertThat(registry.subscriptions(1L)).isEmpty();
        assertThat(listener.left).containsExactly(subscription);
    }

    @Test
    @DisplayName("close(subscription) 은 맵에서 빼고 emitter 를 끝낸다 - 이미 빠진 구독이면 아무것도 안 한다")
    void close_removesAndCompletesOnce() {
        Subscription subscription = registry.register(1L, USER);

        registry.close(subscription);
        registry.close(subscription);

        assertThat(isClosed(subscription.emitter())).isTrue();
        assertThat(listener.left).containsExactly(subscription);
    }

    // ---- 전송 ----

    @Test
    @DisplayName("[QUIZ-CQR-90] publish 는 그 방의 모든 구독자(제출자 포함, exclude=null)에게 보내고 다른 방에는 보내지 않는다")
    void publish_reachesEverySubscriberOfThatRoomOnly() throws Exception {
        Subscription a = registry.register(1L, USER);
        Subscription b = registry.register(1L, OTHER);
        Subscription elsewhere = registry.register(2L, 9L);

        registry.publish(1L, new RealtimeEvent("votes", "payload", null));

        assertThat(earlySendCount(a.emitter())).isPositive();
        assertThat(earlySendCount(b.emitter())).isEqualTo(earlySendCount(a.emitter()));
        assertThat(earlySendCount(elsewhere.emitter())).isZero();
    }

    @Test
    @DisplayName("publish 에 제외 대상이 있으면 그 계정만 건너뛴다")
    void publish_skipsExcludedAccount() throws Exception {
        Subscription excluded = registry.register(1L, USER);
        Subscription other = registry.register(1L, OTHER);

        registry.publish(1L, new RealtimeEvent("votes", "payload", USER));

        assertThat(earlySendCount(excluded.emitter())).isZero();
        assertThat(earlySendCount(other.emitter())).isPositive();
    }

    @Test
    @DisplayName("구독자가 없는 방에 publish 해도 예외가 없다")
    void publish_noSubscribers_isNoOp() {
        assertThatCode(() -> registry.publish(404L, new RealtimeEvent("votes", "x", null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("send 가 실패하면 false 를 반환하고 그 구독을 회수한다")
    void send_toDeadConnection_returnsFalseAndRemoves() {
        Subscription dead = registry.register(1L, USER);
        dead.emitter().complete();

        boolean sent = registry.send(dead, "presence", "x");

        assertThat(sent).isFalse();
        assertThat(registry.subscriptions(1L)).isEmpty();
        assertThat(listener.left).containsExactly(dead);
    }

    @Test
    @DisplayName("send 가 성공하면 true 를 반환한다")
    void send_toLiveConnection_returnsTrue() {
        Subscription live = registry.register(1L, USER);

        assertThat(registry.send(live, "presence", "x")).isTrue();
    }

    @Test
    @DisplayName("하트비트는 죽은 연결을 회수하고 인원 집계의 생존 갱신을 호출한다")
    void heartbeat_removesDeadAndTouchesListener() {
        Subscription dead = registry.register(1L, USER);
        Subscription live = registry.register(1L, OTHER);
        dead.emitter().complete();

        registry.heartbeat();

        assertThat(registry.subscriptions(1L)).containsExactly(live);
        assertThat(listener.heartbeats).isEqualTo(1);
    }

    // ---- 버스에서 온 이벤트 ----

    @Test
    @DisplayName("[QUIZ-CQR-93] deliver: 다른 파드에서 온 votes 이벤트는 이 파드의 그 방 구독자에게 전송된다")
    void deliver_dataEvent_sentToLocalSubscribers() throws Exception {
        Subscription local = registry.register(5L, USER);

        registry.deliver("quiz-room:5", new RealtimeEvent("votes", "payload", null), null);

        assertThat(earlySendCount(local.emitter())).isPositive();
    }

    @Test
    @DisplayName("deliver: roomId 로 파싱되지 않는 토픽은 무시한다")
    void deliver_malformedTopic_isIgnored() throws Exception {
        Subscription local = registry.register(5L, USER);

        registry.deliver("quiz-room:abc", new RealtimeEvent("votes", "payload", null), null);
        registry.deliver("room-uid", new RealtimeEvent("votes", "payload", null), null);

        assertThat(earlySendCount(local.emitter())).isZero();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] deliver: 다른 파드가 낸 evict 명령은 이 파드의 그 계정 퀴즈방 구독을 방 불문 끝낸다")
    void deliver_evictFromOtherPod_closesAccountSubscriptions() {
        Subscription room1 = registry.register(1L, USER);
        Subscription room2 = registry.register(2L, USER);
        Subscription other = registry.register(1L, OTHER);
        SubscriptionCloseCommand evict = SubscriptionCloseCommand.evict(USER, "other-pod");

        registry.deliver("quiz-room:3", evict.toEvent(), evict);

        // register(2) 가 이미 room1 을 축출했으므로 room1 은 닫혀 있고, 남은 USER 구독(room2)이 이번 명령으로 끝난다
        assertThat(isClosed(room1.emitter())).isTrue();
        assertThat(isClosed(room2.emitter())).isTrue();
        assertThat(isClosed(other.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] deliver: 다른 파드가 낸 leave 명령은 그 방의 그 계정 구독만 끝낸다")
    void deliver_leaveFromOtherPod_closesOnlyThatRoomSubscription() {
        Subscription mine = registry.register(1L, USER);
        Subscription others = registry.register(1L, OTHER);
        SubscriptionCloseCommand leave = SubscriptionCloseCommand.leave(USER, "other-pod");

        registry.deliver("quiz-room:1", leave.toEvent(), leave);

        assertThat(isClosed(mine.emitter())).isTrue();
        assertThat(isClosed(others.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] deliver: 자기 파드가 낸 명령(originInstanceId 일치)은 무시한다 - 방금 만든 새 구독을 자기 명령으로 끊지 않는다")
    void deliver_ownCommand_isIgnored() {
        Subscription mine = registry.register(1L, USER);
        SubscriptionCloseCommand own = SubscriptionCloseCommand.evict(USER, registry.instanceId());

        registry.deliver("quiz-room:1", own.toEvent(), own);

        assertThat(registry.subscriptions(1L)).containsExactly(mine);
        assertThat(isClosed(mine.emitter())).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 종료 명령은 구독자에게 data: 프레임으로 새지 않는다 (전송이 아니라 연결 종료로만 처리)")
    void deliver_closeCommand_isNeverSentAsFrame() throws Exception {
        Subscription bystander = registry.register(1L, OTHER);
        SubscriptionCloseCommand leave = SubscriptionCloseCommand.leave(USER, "other-pod");

        registry.deliver("quiz-room:1", leave.toEvent(), leave);

        assertThat(earlySendCount(bystander.emitter())).isZero();
    }

    @Test
    @DisplayName("인스턴스 id 는 파드마다 다르다 (자기 명령 무시 판정의 근거)")
    void instanceId_isUniquePerRegistry() {
        assertThat(new QuizRoomSseRegistry().instanceId()).isNotEqualTo(new QuizRoomSseRegistry().instanceId());
    }
}
