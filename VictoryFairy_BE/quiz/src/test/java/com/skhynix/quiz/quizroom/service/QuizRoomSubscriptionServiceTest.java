package com.skhynix.quiz.quizroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.quizroom.repository.QuizRoomRepository;
import com.skhynix.quiz.quizroom.dto.VotesPayload;
import com.skhynix.quiz.quizroom.realtime.QuizRoomPresenceTracker;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry;
import com.skhynix.quiz.realtime.QuizRoomSseRegistry.Subscription;
import com.skhynix.quiz.realtime.RealtimeEvent;
import com.skhynix.quiz.realtime.RealtimeEventPublisher;
import com.skhynix.quiz.realtime.SseEmitterRegistry;
import com.skhynix.quiz.realtime.SubscriptionCloseCommand;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구독 서비스. 레지스트리는 실물(스파이)을 써서 "퀴즈방 구독이 채팅 레지스트리를 건드리지 않는다"를 실제 맵 동작으로 본다.
 * 죽은 연결은 이미 complete() 된 emitter 에 send() 하면 IllegalStateException 이 나는 공개 동작으로 판별한다.
 */
@ExtendWith(MockitoExtension.class)
class QuizRoomSubscriptionServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private QuizRoomRepository roomRepository;
    @Mock
    private QuizRoomPresenceTracker presenceTracker;
    @Mock
    private QuizRoomVoteReader voteReader;
    @Mock
    private RealtimeEventPublisher eventPublisher;

    private QuizRoomSseRegistry registry;
    private QuizRoomSubscriptionService service;

    @BeforeEach
    void setUp() {
        registry = spy(new QuizRoomSseRegistry());
        service = new QuizRoomSubscriptionService(roomRepository, registry, presenceTracker, voteReader,
                eventPublisher);
    }

    private static boolean isClosed(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("probe"));
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static VotesPayload votes(long quizId) {
        return new VotesPayload(quizId, List.of(new VotesPayload.OptionCount(0, 0), new VotesPayload.OptionCount(1, 0)));
    }

    @Test
    @DisplayName("[QUIZ-CQR-81] 없는 방 구독은 QUIZ_ROOM_NOT_FOUND 이고 레지스트리 등록·인원 등록·분포 조회·축출 발행이 하나도 일어나지 않는다")
    void subscribe_roomNotFound_opensNothing() {
        given(roomRepository.existsById(999999L)).willReturn(false);

        assertThatThrownBy(() -> service.subscribe(999999L, USER_ID)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        assertThat(registry.roomIds()).isEmpty();
        verifyNoInteractions(presenceTracker, voteReader, eventPublisher);
    }

    @Test
    @DisplayName("[QUIZ-CQR-80, QUIZ-CQR-82, QUIZ-CQR-88] 구독이 성립하면 퀴즈방 레지스트리에 등록되고, 인원 등록(첫 presence)을 거친 뒤 문항마다 votes 를 1회씩 그 구독자에게만 보낸다")
    void subscribe_registersJoinsAndSendsVotesPerQuiz() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of(votes(11L), votes(12L), votes(13L)));

        SseEmitter emitter = service.subscribe(1L, USER_ID);

        assertThat(registry.subscriptions(1L)).hasSize(1);
        Subscription subscription = registry.subscriptions(1L).iterator().next();
        assertThat(subscription.emitter()).isSameAs(emitter);
        assertThat(subscription.userAccountId()).isEqualTo(USER_ID);
        verify(presenceTracker).joined(subscription);
        verify(registry).send(subscription, "votes", votes(11L));
        verify(registry).send(subscription, "votes", votes(12L));
        verify(registry).send(subscription, "votes", votes(13L));
        verify(registry, org.mockito.Mockito.times(3)).send(eq(subscription), eq("votes"), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-82, QUIZ-CQR-88] 순서: 방 확인 -> 등록 -> 인원 등록(presence) -> 분포 조회/전송 -> 다른 파드 축출 명령")
    void subscribe_followsDocumentedOrder() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of(votes(11L)));

        service.subscribe(1L, USER_ID);

        InOrder order = inOrder(roomRepository, registry, presenceTracker, voteReader, eventPublisher);
        order.verify(roomRepository).existsById(1L);
        order.verify(registry).register(1L, USER_ID);
        order.verify(presenceTracker).joined(any(Subscription.class));
        order.verify(voteReader).readRoom(1L);
        order.verify(registry).send(any(Subscription.class), eq("votes"), any());
        order.verify(eventPublisher).publish(eq("quiz-room:1"), any(RealtimeEvent.class));
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 구독 성립 시 같은 계정의 다른 파드 구독을 끝내도록 quiz-room:{roomId} 토픽에 evict 명령(이 파드 instanceId 포함)을 발행한다")
    void subscribe_publishesEvictCommandOnQuizRoomTopic() {
        given(roomRepository.existsById(2L)).willReturn(true);
        given(voteReader.readRoom(2L)).willReturn(List.of());

        service.subscribe(2L, USER_ID);

        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq("quiz-room:2"), event.capture());
        assertThat(SubscriptionCloseCommand.isCloseSignal(event.getValue())).isTrue();
        SubscriptionCloseCommand command = (SubscriptionCloseCommand) event.getValue().data();
        assertThat(command.targetUserAccountId()).isEqualTo(USER_ID);
        assertThat(command.originInstanceId()).isEqualTo(registry.instanceId());
        assertThat(command.allRooms()).isTrue();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 같은 계정이 방 2 를 구독하면 방 1 구독의 스트림이 끝나고 방 1 레지스트리에서 빠진다")
    void subscribe_otherRoom_closesPreviousRoomSubscription() {
        given(roomRepository.existsById(anyLong())).willReturn(true);
        given(voteReader.readRoom(anyLong())).willReturn(List.of());
        SseEmitter first = service.subscribe(1L, USER_ID);

        SseEmitter second = service.subscribe(2L, USER_ID);

        assertThat(registry.subscriptions(1L)).isEmpty();
        assertThat(registry.subscriptions(2L)).hasSize(1);
        assertThat(isClosed(first)).isTrue();
        assertThat(isClosed(second)).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-92] 다른 계정의 구독은 끝나지 않는다")
    void subscribe_otherAccount_keepsExistingSubscription() {
        given(roomRepository.existsById(anyLong())).willReturn(true);
        given(voteReader.readRoom(anyLong())).willReturn(List.of());
        SseEmitter other = service.subscribe(1L, 99L);

        service.subscribe(2L, USER_ID);

        assertThat(registry.subscriptions(1L)).hasSize(1);
        assertThat(isClosed(other)).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-91] 퀴즈방 구독은 같은 계정의 /rt/chat 구단 채팅 구독(SseEmitterRegistry)을 끊지 않는다")
    void subscribe_doesNotEvictChatSubscription() {
        SseEmitterRegistry chatRegistry = new SseEmitterRegistry();
        SseEmitter chatEmitter = chatRegistry.register("chat-room-uid", USER_ID);
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of());

        SseEmitter quizEmitter = service.subscribe(1L, USER_ID);

        assertThat(chatRegistry.count("chat-room-uid")).isEqualTo(1);
        assertThat(isClosed(chatEmitter)).isFalse();
        assertThat(isClosed(quizEmitter)).isFalse();
    }

    @Test
    @DisplayName("[QUIZ-CQR-91] 반대로 채팅 구독이 새로 성립해도 이미 열린 퀴즈방 구독은 끊기지 않는다")
    void chatSubscribe_doesNotEvictQuizRoomSubscription() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of());
        SseEmitter quizEmitter = service.subscribe(1L, USER_ID);

        new SseEmitterRegistry().register("chat-room-uid", USER_ID);

        assertThat(registry.subscriptions(1L)).hasSize(1);
        assertThat(isClosed(quizEmitter)).isFalse();
    }

    @Test
    @DisplayName("분포 조회가 실패하면 등록한 구독을 레지스트리에서 걷어 내고 예외를 그대로 던진다 (응답으로 못 나간 emitter 가 남지 않는다)")
    void subscribe_voteReadFailure_closesSubscriptionAndRethrows() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.subscribe(1L, USER_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(registry.subscriptions(1L)).isEmpty();
        verify(eventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("[QUIZ-CQR-96] 축출 명령 발행이 실패해도 구독은 성립한다 (실시간 전달 실패가 구독을 되돌리지 않는다)")
    void subscribe_evictPublishFailure_isSwallowed() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of());
        willThrow(new IllegalStateException("redis down")).given(eventPublisher).publish(anyString(), any());

        assertThatCode(() -> service.subscribe(1L, USER_ID)).doesNotThrowAnyException();
        assertThat(registry.subscriptions(1L)).hasSize(1);
    }

    // ---- 퇴장 ----

    @Test
    @DisplayName("[QUIZ-CQR-85] 퇴장하면 그 방의 그 계정 구독을 끝내고 다른 계정·다른 방 구독은 그대로 둔다")
    void unsubscribe_closesOnlyThatAccountsSubscriptionInThatRoom() {
        given(roomRepository.existsById(anyLong())).willReturn(true);
        given(voteReader.readRoom(anyLong())).willReturn(List.of());
        SseEmitter mine = service.subscribe(1L, USER_ID);
        SseEmitter others = service.subscribe(1L, 99L);

        service.unsubscribe(1L, USER_ID);

        assertThat(isClosed(mine)).isTrue();
        assertThat(isClosed(others)).isFalse();
        assertThat(registry.subscriptions(1L)).hasSize(1);
    }

    @Test
    @DisplayName("[QUIZ-CQR-85] 퇴장은 다른 파드의 같은 계정 구독도 끝내도록 leave 명령(allRooms=false)을 방 토픽에 발행한다")
    void unsubscribe_publishesLeaveCommand() {
        service.unsubscribe(1L, USER_ID);

        ArgumentCaptor<RealtimeEvent> event = ArgumentCaptor.forClass(RealtimeEvent.class);
        verify(eventPublisher).publish(eq("quiz-room:1"), event.capture());
        SubscriptionCloseCommand command = (SubscriptionCloseCommand) event.getValue().data();
        assertThat(command.allRooms()).isFalse();
        assertThat(command.targetUserAccountId()).isEqualTo(USER_ID);
        assertThat(command.originInstanceId()).isEqualTo(registry.instanceId());
    }

    @Test
    @DisplayName("[QUIZ-CQR-87] 구독이 없는 상태·존재하지 않는 방에서 퇴장해도 예외 없이 끝나고 방 존재를 조회하지 않는다")
    void unsubscribe_isFullyIdempotent() {
        assertThatCode(() -> {
            service.unsubscribe(999999L, USER_ID);
            service.unsubscribe(999999L, USER_ID);
        }).doesNotThrowAnyException();

        verifyNoInteractions(roomRepository);
    }

    @Test
    @DisplayName("[QUIZ-CQR-87, QUIZ-CQR-96] 퇴장 명령 발행이 실패해도 퇴장은 성공하고 이 파드의 구독은 이미 끝나 있다")
    void unsubscribe_publishFailure_isSwallowed() {
        given(roomRepository.existsById(1L)).willReturn(true);
        given(voteReader.readRoom(1L)).willReturn(List.of());
        SseEmitter mine = service.subscribe(1L, USER_ID);
        willThrow(new IllegalStateException("redis down")).given(eventPublisher)
                .publish(eq("quiz-room:1"), org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && SubscriptionCloseCommand.isCloseSignal(event)
                                && !((SubscriptionCloseCommand) event.data()).allRooms()));

        assertThatCode(() -> service.unsubscribe(1L, USER_ID)).doesNotThrowAnyException();
        assertThat(isClosed(mine)).isTrue();
    }
}
