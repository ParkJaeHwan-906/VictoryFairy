package com.skhynix.quiz.quizroom.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.skhynix.quiz.quizroom.dto.PresencePayload;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry.Subscription;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 인원 집계 트래커. Redis 저장소는 목이라 "고유 계정 수를 모든 파드에서 합친다"(QUIZ-CQR-83)와 만료 창(QUIZ-CQR-86)은
 * 여기서 검증하지 못한다(Lua 스크립트라 실제 Redis 필요). 여기서는 트래커가 저장소를 <b>언제 어떤 인자로</b> 부르고
 * 구독자에게 <b>언제 무엇을</b> 보내는지만 고정한다. 주기 작업은 간격을 사실상 무한대로 두고 tick() 을 직접 부른다.
 */
@ExtendWith(MockitoExtension.class)
class QuizRoomPresenceTrackerTest {

    private static final Long USER = 7L;
    private static final Long OTHER = 8L;

    @Mock
    private QuizRoomPresenceStore store;

    private QuizRoomSseRegistry registry;
    private QuizRoomPresenceTracker tracker;
    private String instanceId;

    @BeforeEach
    void setUp() {
        registry = spy(new QuizRoomSseRegistry());
        instanceId = registry.instanceId();
        tracker = new QuizRoomPresenceTracker(registry, store, 3_600_000L, Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        tracker.shutdown();
    }

    @Test
    @DisplayName("[QUIZ-CQR-82] 구독이 성립하면 소유자를 자기로 덮어쓰며 등록하고, 그 구독자에게 현재 인원을 presence 로 1회 보낸다")
    void joined_registersAndSendsFirstPresence() {
        Subscription subscription = registry.register(1L, USER);
        given(store.join(1L, List.of(USER), instanceId)).willReturn(1L);

        tracker.joined(subscription);

        verify(registry).send(subscription, "presence", new PresencePayload(1L));
        assertThat(subscription.presenceSent()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-84] 등록 전에 이미 닫힌 구독은 인원에 등록하지 않는다 (퇴장이 먼저 처리된 기록을 되살리지 않는다)")
    void joined_alreadyClosedSubscription_isNotRegistered() {
        Subscription subscription = registry.register(1L, USER);
        registry.close(subscription);

        tracker.joined(subscription);

        verify(store, never()).join(anyLong(), anyList(), anyString());
    }

    @Test
    @DisplayName("Redis 등록이 실패하면 스트림은 닫지 않고, 구독을 '값 모름' 상태로 둬 다음 주기 전달이 현재 값을 보내게 한다")
    void joined_storeFailure_marksPresenceUnknown() {
        Subscription subscription = registry.register(1L, USER);
        given(store.join(anyLong(), anyList(), anyString())).willThrow(new IllegalStateException("redis down"));

        tracker.joined(subscription);

        assertThat(subscription.presenceSent()).isEqualTo(QuizRoomSseRegistry.PRESENCE_UNKNOWN);
        assertThat(registry.subscriptions(1L)).containsExactly(subscription);
    }

    @Test
    @DisplayName("[QUIZ-CQR-84] 주기 전달: 마지막으로 보낸 값과 다르면 새 인원을 보낸다 (계정 1 구독 중 계정 2 가 들어와 count 2)")
    void tick_countChanged_sendsNewPresence() {
        Subscription first = registry.register(1L, USER);
        given(store.join(1L, List.of(USER), instanceId)).willReturn(1L);
        tracker.joined(first);
        given(store.count(1L)).willReturn(2L);

        tracker.tick();

        verify(registry).send(first, "presence", new PresencePayload(2L));
        assertThat(first.presenceSent()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-84] 주기 전달: 값이 그대로면 프레임을 내지 않는다")
    void tick_countUnchanged_sendsNothing() {
        Subscription first = registry.register(1L, USER);
        given(store.join(1L, List.of(USER), instanceId)).willReturn(3L);
        tracker.joined(first);
        given(store.count(1L)).willReturn(3L);

        tracker.tick();
        tracker.tick();

        verify(registry, org.mockito.Mockito.times(1)).send(any(Subscription.class), anyString(), any());
    }

    @Test
    @DisplayName("주기 전달: 인원 등록 전(NOT_JOINED) 구독은 건너뛴다 - 첫 값은 등록이 보낸다")
    void tick_notJoinedSubscription_isSkipped() {
        Subscription notJoined = registry.register(1L, USER);
        given(store.count(1L)).willReturn(5L);

        tracker.tick();

        verify(registry, never()).send(any(Subscription.class), anyString(), any());
        assertThat(notJoined.presenceSent()).isEqualTo(QuizRoomSseRegistry.PRESENCE_NOT_JOINED);
    }

    @Test
    @DisplayName("주기 전달: 값 모름(UNKNOWN) 구독에는 어떤 값이든 다르다고 보고 한 번 보낸다")
    void tick_unknownSubscription_receivesCurrentValue() {
        Subscription unknown = registry.register(1L, USER);
        unknown.presenceSent(QuizRoomSseRegistry.PRESENCE_UNKNOWN);
        given(store.count(1L)).willReturn(0L);

        tracker.tick();

        verify(registry).send(unknown, "presence", new PresencePayload(0L));
    }

    @Test
    @DisplayName("[QUIZ-CQR-84] 주기 전달: Redis 조회가 실패하면 대체 값을 보내지 않고 스트림도 닫지 않는다 (화면에는 마지막 값이 남는다)")
    void tick_countFailure_sendsNothingAndKeepsStream() {
        Subscription first = registry.register(1L, USER);
        first.presenceSent(2L);
        given(store.count(1L)).willThrow(new IllegalStateException("redis down"));

        tracker.tick();

        verify(registry, never()).send(any(Subscription.class), anyString(), any());
        assertThat(registry.subscriptions(1L)).containsExactly(first);
        assertThat(first.presenceSent()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[QUIZ-CQR-84] 주기 전달: 같은 방의 구독자 모두에게 같은 새 값을 보낸다")
    void tick_countChanged_reachesEverySubscriberOfRoom() {
        Subscription a = registry.register(1L, USER);
        Subscription b = registry.register(1L, OTHER);
        a.presenceSent(1L);
        b.presenceSent(1L);
        given(store.count(1L)).willReturn(2L);

        tracker.tick();

        verify(registry).send(a, "presence", new PresencePayload(2L));
        verify(registry).send(b, "presence", new PresencePayload(2L));
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] 구독이 끝나고 이 파드에 그 계정의 그 방 구독이 더 없으면 Redis 에서 그 계정을 뺀다")
    void left_noMoreLocalSubscription_removesAccountFromStore() {
        Subscription subscription = registry.register(1L, USER);
        registry.close(subscription);

        verify(store, timeout(2000)).leave(1L, List.of(USER), instanceId);
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] 같은 파드에서 같은 방을 이미 다시 구독 중이면 옛 구독이 끝나도 인원에서 빼지 않는다")
    void left_stillSubscribedLocallyToSameRoom_doesNotRemove() {
        Subscription old = registry.register(1L, USER);
        Subscription replacement = registry.register(1L, USER);
        // 같은 방 재구독이면 register 가 옛 구독을 이미 끝냈다. 그 퇴장 통보를 처리한 뒤의 상태를 기다린다
        Subscription barrier = registry.register(2L, OTHER);
        given(store.join(anyLong(), anyList(), anyString())).willReturn(1L);
        tracker.joined(barrier);

        verify(store, never()).leave(1L, List.of(USER), instanceId);
        assertThat(registry.subscriptions(1L)).containsExactly(replacement);
        assertThat(old).isNotSameAs(replacement);
    }

    @Test
    @DisplayName("[QUIZ-CQR-83] 생존 갱신(하트비트)은 로컬 구독 계정을 중복 없이 한 번씩만 Redis 에 갱신한다 (같은 계정의 연결이 여러 개여도 1명)")
    void heartbeat_refreshesDistinctAccountsPerRoom() {
        registry.register(1L, USER);
        registry.register(1L, OTHER);
        registry.register(2L, 9L);

        tracker.heartbeat();

        ArgumentCaptor<List<Long>> room1Users = ArgumentCaptor.captor();
        verify(store, timeout(2000)).refresh(org.mockito.ArgumentMatchers.eq(1L), room1Users.capture(),
                org.mockito.ArgumentMatchers.eq(instanceId));
        assertThat(room1Users.getValue()).containsExactlyInAnyOrder(USER, OTHER);
        verify(store, timeout(2000)).refresh(2L, List.of(9L), instanceId);
    }

    @Test
    @DisplayName("생존 갱신: Redis 가 실패하면 이번 주기를 건너뛰고 예외는 전파되지 않는다")
    void heartbeat_storeFailure_isSwallowed() {
        registry.register(1L, USER);
        org.mockito.BDDMockito.willThrow(new IllegalStateException("redis down")).given(store)
                .refresh(anyLong(), anyList(), anyString());

        tracker.heartbeat();

        verify(store, timeout(2000)).refresh(1L, List.of(USER), instanceId);
        assertThat(registry.subscriptions(1L)).hasSize(1);
    }
}
