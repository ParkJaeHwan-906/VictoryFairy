package com.skhynix.chat.subscription.service;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.realtime.ChatSubscription;
import com.skhynix.chat.realtime.SseEmitterRegistry;
import com.skhynix.chat.realtime.SseFrame;
import com.skhynix.chat.realtime.SseFrameWriter;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.chat.shared.kafka.SubscriptionCloseCommand;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 구독·복구·퇴장. 쓰기 풀은 목이라 "어떤 프레임을 어떤 순서로 맡겼는가"를 InOrder 로 본다.
 * 복구 중 실시간 프레임이 복구 뒤에 나가는지(실제 풀 기준)는 {@code SseFrameWriterTest} 가 본다.
 */
class ChatSubscriptionServiceTest {

    private static final String GAME_ID = "G1";
    private static final Long ME = 1L;

    private final ChatRoomGuard roomGuard = mock(ChatRoomGuard.class);
    private final SseFrameWriter writer = mock(SseFrameWriter.class);
    private final ChatStreamReader streamReader = mock(ChatStreamReader.class);
    private final ChatKafkaProducer producer = mock(ChatKafkaProducer.class);
    private SseEmitterRegistry registry;
    private ChatSubscriptionService service;

    @BeforeEach
    void setUp() {
        registry = new SseEmitterRegistry(writer);
        service = new ChatSubscriptionService(props(), roomGuard, registry, writer, streamReader, producer);
        given(streamReader.blinded(anyString(), anyCollection())).willReturn(Set.of());
    }

    private static ChatStreamEntry entry(long msgId, long senderId) {
        return new ChatStreamEntry(msgId, senderId, "닉" + msgId, "OB", null, "내용" + msgId, "t");
    }

    private static List<ChatStreamEntry> range(long fromInclusive, long toInclusive, long senderId) {
        return LongStream.rangeClosed(fromInclusive, toInclusive).mapToObj(i -> entry(i, senderId)).toList();
    }

    private ChatSubscription onlySubscription() {
        return registry.subscriptions(GAME_ID).iterator().next();
    }

    private List<SseFrame> enqueuedFrames() {
        ArgumentCaptor<SseFrame> captor = ArgumentCaptor.forClass(SseFrame.class);
        verify(writer, org.mockito.Mockito.atLeast(0)).enqueue(any(), captor.capture());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> msgIds(SseFrame frame) {
        return ((List<ChatMessageView>) frame.data()).stream().map(ChatMessageView::msgId).toList();
    }

    // ---------- 구독 성립 ----------

    @Test
    @DisplayName("[CHAT-GC-30] 구독하면 방을 확인하고 emitter 를 돌려주며 레지스트리에 등록한다")
    void subscribe_registersAndReturnsEmitter() {
        SseEmitter emitter = service.subscribe(GAME_ID, ME, null);

        verify(roomGuard).requireOrCreate(GAME_ID);
        assertThat(registry.count(GAME_ID)).isEqualTo(1);
        assertThat(onlySubscription().emitter()).isSameAs(emitter);
    }

    @Test
    @DisplayName("[CHAT-GC-31] 방이 없으면(404) 스트림을 열지 않고 레지스트리에 등록하지 않으며 종료 명령도 발행하지 않는다")
    void subscribe_roomMissing_registersNothing() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND)).when(roomGuard).requireOrCreate(GAME_ID);

        assertBusiness(() -> service.subscribe(GAME_ID, ME, null), ErrorCode.CHATROOM_NOT_FOUND);

        assertThat(registry.count(GAME_ID)).isZero();
        verifyNoInteractions(producer, streamReader);
    }

    @Test
    @DisplayName("[CHAT-GC-29] 방 확인 중 Redis 장애면 503 이고 등록하지 않는다")
    void subscribe_redisDown_is503() {
        doThrow(new BusinessException(ErrorCode.CHAT_BROKER_UNAVAILABLE)).when(roomGuard).requireOrCreate(GAME_ID);

        assertBusiness(() -> service.subscribe(GAME_ID, ME, null), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        assertThat(registry.count(GAME_ID)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-35] 구독이 성립하면 chat-control 로 방 불문 축출 명령(allRooms=true)을 이 인스턴스 id 와 함께 발행한다")
    void subscribe_publishesEvictCommandWithOwnInstanceId() {
        service.subscribe(GAME_ID, ME, null);

        verify(producer).sendControl(SubscriptionCloseCommand.evict(ME, registry.instanceId()));
    }

    @Test
    @DisplayName("[CHAT-GC-35] 축출 명령이 이 인스턴스로 되돌아와도 방금 만든 구독은 살아 있다(통합: 발행한 명령을 그대로 레지스트리에 먹여 본다)")
    void subscribe_ownEvictCommandEchoedBack_keepsNewSubscription() {
        ArgumentCaptor<SubscriptionCloseCommand> sent = ArgumentCaptor.forClass(SubscriptionCloseCommand.class);
        service.subscribe(GAME_ID, ME, null);
        verify(producer).sendControl(sent.capture());

        registry.handleCloseCommand(sent.getValue());

        assertThat(registry.count(GAME_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("[CHAT-GC-34] 같은 사용자가 재구독하면 기존 로컬 구독은 즉시 닫히고 구독은 하나만 남는다")
    void subscribe_again_evictsPreviousLocalSubscription() {
        service.subscribe(GAME_ID, ME, null);
        ChatSubscription first = onlySubscription();

        service.subscribe(GAME_ID, ME, null);

        assertThat(registry.count(GAME_ID)).isEqualTo(1);
        assertThat(onlySubscription()).isNotSameAs(first);
        verify(writer).complete(first.emitter());
    }

    @Test
    @DisplayName("[CHAT-GC-48] 축출 명령 발행이 실패해도 구독은 정상 성립한다(다른 파드는 안전망으로 회수)")
    void subscribe_evictPublishFails_stillSucceeds() {
        doThrow(new ChatPublishException("down", new RuntimeException())).when(producer).sendControl(any());

        assertThatCode(() -> service.subscribe(GAME_ID, ME, null)).doesNotThrowAnyException();
        assertThat(registry.count(GAME_ID)).isEqualTo(1);
    }

    // ---------- Last-Event-ID 해석 (41) ----------

    @Test
    @DisplayName("[CHAT-GC-41] Last-Event-ID 는 0 이상의 정수만 인정한다 — 공백은 trim, 음수·소수·문자·overflow 는 무시(null)")
    void parseLastEventId_acceptsOnlyNonNegativeIntegers() {
        assertThat(ChatSubscriptionService.parseLastEventId("120")).isEqualTo(120L);
        assertThat(ChatSubscriptionService.parseLastEventId(" 5 ")).isEqualTo(5L);
        assertThat(ChatSubscriptionService.parseLastEventId("0")).isEqualTo(0L);
        assertThat(ChatSubscriptionService.parseLastEventId("-1")).isNull();
        assertThat(ChatSubscriptionService.parseLastEventId("abc")).isNull();
        assertThat(ChatSubscriptionService.parseLastEventId("1.5")).isNull();
        assertThat(ChatSubscriptionService.parseLastEventId("99999999999999999999")).isNull();
        assertThat(ChatSubscriptionService.parseLastEventId(null)).isNull();
        assertThat(ChatSubscriptionService.parseLastEventId("  ")).isNull();
    }

    @ParameterizedTest(name = "[CHAT-GC-41] Last-Event-ID=\"{0}\" 이면 복구도 reset 도 없이 실시간만 시작한다")
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "-3", "1.2", "0x10", "99999999999999999999"})
    void subscribe_unparsableLastEventId_noRecoveryNoReset(String header) {
        service.subscribe(GAME_ID, ME, header);

        verifyNoInteractions(streamReader);
        verify(writer, never()).enqueue(any(), any());
        verify(writer, never()).goLive(any());
    }

    // ---------- 복구 (37~40, 42) ----------

    @Test
    @DisplayName("[CHAT-GC-37] Last-Event-ID 가 있으면 그 offset 뒤의 엔트리를 한 페이지 = messages 이벤트 하나(id=마지막 offset)로 먼저 흘리고 그 다음 goLive 한다")
    void subscribe_withLastEventId_sendsRecoveredPageThenGoesLive() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(100, 2)));
        given(streamReader.after(GAME_ID, 120L, 500)).willReturn(range(121, 125, 2));

        service.subscribe(GAME_ID, ME, "120");

        ChatSubscription subscription = onlySubscription();
        InOrder order = inOrder(writer);
        ArgumentCaptor<SseFrame> frame = ArgumentCaptor.forClass(SseFrame.class);
        order.verify(writer).enqueue(org.mockito.ArgumentMatchers.eq(subscription), frame.capture());
        order.verify(writer).goLive(subscription);
        assertThat(frame.getValue().name()).isEqualTo("messages");
        assertThat(frame.getValue().id()).isEqualTo("125");
        assertThat(msgIds(frame.getValue())).containsExactly(121L, 122L, 123L, 124L, 125L);
    }

    @Test
    @DisplayName("[CHAT-GC-37] 복구 대상이 하나도 없으면(놓친 것 없음) 이벤트 없이 바로 실시간에 합류한다")
    void subscribe_nothingMissed_noFrameThenLive() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(100, 2)));
        given(streamReader.after(GAME_ID, 120L, 500)).willReturn(List.of());

        service.subscribe(GAME_ID, ME, "120");

        verify(writer, never()).enqueue(any(), any());
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-37] Stream 이 비어 있으면(oldest 없음) 복구 없이 합류하고 reset 도 보내지 않는다")
    void subscribe_emptyStream_noRecoveryNoReset() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.empty());

        service.subscribe(GAME_ID, ME, "120");

        verify(streamReader, never()).after(anyString(), anyLong(), anyInt());
        verify(writer, never()).enqueue(any(), any());
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-38] 복구 이벤트에서 blind 된 메시지와 구독자 본인의 메시지는 제외된다")
    void subscribe_recovery_excludesBlindAndOwnMessages() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(100, 2)));
        List<ChatStreamEntry> page = new ArrayList<>(range(121, 125, 2));
        page.set(1, entry(122, ME));   // 본인
        given(streamReader.after(GAME_ID, 120L, 500)).willReturn(page);
        given(streamReader.blinded(eq(GAME_ID), anyCollection())).willReturn(Set.of(124L));

        service.subscribe(GAME_ID, ME, "120");

        List<SseFrame> frames = enqueuedFrames();
        assertThat(frames).hasSize(1);
        assertThat(msgIds(frames.get(0))).containsExactly(121L, 123L, 125L);
        assertThat(frames.get(0).id()).as("id 는 걸러내기 전 페이지의 마지막 offset").isEqualTo("125");
    }

    @Test
    @DisplayName("[CHAT-GC-38] 페이지 전체가 걸러져 비면 그 페이지 이벤트는 보내지 않는다")
    void subscribe_recovery_pageFullyFiltered_sendsNothing() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(100, 2)));
        given(streamReader.after(GAME_ID, 120L, 500)).willReturn(range(121, 122, ME));

        service.subscribe(GAME_ID, ME, "120");

        verify(writer, never()).enqueue(any(), any());
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-40] Last-Event-ID 가 Stream 의 가장 오래된 엔트리보다 작으면 복구 없이 reset 하나만 보낸다")
    void subscribe_lastEventIdOlderThanOldest_sendsResetOnly() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(500, 2)));

        service.subscribe(GAME_ID, ME, "120");

        verify(streamReader, never()).after(anyString(), anyLong(), anyInt());
        List<SseFrame> frames = enqueuedFrames();
        assertThat(frames).extracting(SseFrame::name).containsExactly("reset");
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-40] Last-Event-ID 가 가장 오래된 엔트리와 같으면(경계) reset 이 아니라 정상 복구한다")
    void subscribe_lastEventIdEqualsOldest_recoversNormally() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(500, 2)));
        given(streamReader.after(GAME_ID, 500L, 500)).willReturn(range(501, 502, 2));

        service.subscribe(GAME_ID, ME, "500");

        assertThat(enqueuedFrames()).extracting(SseFrame::name).containsExactly("messages");
    }

    @Test
    @DisplayName("[CHAT-GC-39] 놓친 건수가 정확히 2,500건(500×5 페이지)이고 그 뒤에 더 없으면 reset 없이 messages 5개만 보낸다")
    void subscribe_exactlyMaxBatchesFull_noReset() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(1, 2)));
        for (int page = 0; page < 5; page++) {
            long cursor = 1000L + page * 500L;
            given(streamReader.after(GAME_ID, cursor, 500)).willReturn(range(cursor + 1, cursor + 500, 2));
        }
        given(streamReader.after(GAME_ID, 3500L, 1)).willReturn(List.of());

        service.subscribe(GAME_ID, ME, "1000");

        List<SseFrame> frames = enqueuedFrames();
        assertThat(frames).extracting(SseFrame::name).containsExactly("messages", "messages", "messages", "messages", "messages");
        assertThat(frames.get(4).id()).isEqualTo("3500");
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-39] 2,500건을 채우고도 더 남아 있으면(2,501건째 존재) messages 5개 뒤 reset 하나를 보내고 복구를 중단한다")
    void subscribe_moreThanMaxBatches_sendsResetAfterFivePages() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(1, 2)));
        for (int page = 0; page < 5; page++) {
            long cursor = 1000L + page * 500L;
            given(streamReader.after(GAME_ID, cursor, 500)).willReturn(range(cursor + 1, cursor + 500, 2));
        }
        given(streamReader.after(GAME_ID, 3500L, 1)).willReturn(range(3501, 3501, 2));

        service.subscribe(GAME_ID, ME, "1000");

        List<SseFrame> frames = enqueuedFrames();
        assertThat(frames).extracting(SseFrame::name)
                .containsExactly("messages", "messages", "messages", "messages", "messages", "reset");
        // 6번째 페이지는 읽지 않는다
        verify(streamReader, never()).after(GAME_ID, 3500L, 500);
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-39] 마지막 페이지가 batch-size 미만이면 거기서 멈추고 reset 하지 않는다")
    void subscribe_partialLastPage_stopsWithoutReset() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(1, 2)));
        given(streamReader.after(GAME_ID, 1000L, 500)).willReturn(range(1001, 1500, 2));
        given(streamReader.after(GAME_ID, 1500L, 500)).willReturn(range(1501, 1510, 2));

        service.subscribe(GAME_ID, ME, "1000");

        assertThat(enqueuedFrames()).extracting(SseFrame::name).containsExactly("messages", "messages");
        verify(streamReader, times(2)).after(anyString(), anyLong(), eq(500));
    }

    @Test
    @DisplayName("[CHAT-GC-42] 복구 중 Redis 명령이 실패하면 reset 하나를 보내고 스트림을 유지한 채 실시간에 합류한다")
    void subscribe_recoveryRedisFailure_sendsResetAndStaysSubscribed() {
        given(streamReader.oldest(GAME_ID)).willReturn(Optional.of(entry(100, 2)));
        given(streamReader.after(GAME_ID, 120L, 500)).willThrow(new QueryTimeoutException("timeout"));

        SseEmitter emitter = service.subscribe(GAME_ID, ME, "120");

        assertThat(emitter).isNotNull();
        assertThat(enqueuedFrames()).extracting(SseFrame::name).containsExactly("reset");
        verify(writer).goLive(onlySubscription());
        assertThat(registry.count(GAME_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("[CHAT-GC-42] oldest 조회에서 Redis 가 실패해도 reset 후 합류한다")
    void subscribe_oldestLookupFails_sendsReset() {
        given(streamReader.oldest(GAME_ID)).willThrow(new QueryTimeoutException("timeout"));

        service.subscribe(GAME_ID, ME, "120");

        assertThat(enqueuedFrames()).extracting(SseFrame::name).containsExactly("reset");
        verify(writer).goLive(onlySubscription());
    }

    @Test
    @DisplayName("[CHAT-GC-37] 구독자는 복구 전에 이미 등록되어 있다(복구를 읽는 사이의 메시지를 놓치지 않는다) — 복구 조회 시점에 레지스트리에 있다")
    void subscribe_registersBeforeRecovering() {
        given(streamReader.oldest(GAME_ID)).willAnswer(invocation -> {
            assertThat(registry.count(GAME_ID)).isEqualTo(1);
            return Optional.empty();
        });

        service.subscribe(GAME_ID, ME, "120");

        verify(streamReader).oldest(GAME_ID);
    }

    // ---------- 퇴장 (45~49) ----------

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장하면 로컬 구독을 닫고 allRooms=false + gameId 의 종료 명령을 발행한다")
    void unsubscribe_closesLocalAndPublishesLeaveCommand() {
        service.subscribe(GAME_ID, ME, null);
        ChatSubscription subscription = onlySubscription();

        service.unsubscribe(GAME_ID, ME);

        assertThat(registry.count(GAME_ID)).isZero();
        verify(writer).complete(subscription.emitter());
        verify(producer).sendControl(SubscriptionCloseCommand.leave(ME, registry.instanceId(), GAME_ID));
    }

    @Test
    @DisplayName("[CHAT-GC-46] 퇴장은 방 존재를 확인하지 않는다 — 방 가드를 호출하지 않아 없는 방에도 성공한다")
    void unsubscribe_doesNotCheckRoomExistence() {
        service.unsubscribe("어제-방", ME);

        verifyNoInteractions(roomGuard);
    }

    @Test
    @DisplayName("[CHAT-GC-47] 종료할 구독이 없는 상태의 연속 퇴장은 모두 예외 없이 끝난다")
    void unsubscribe_noSubscription_isIdempotent() {
        assertThatCode(() -> {
            service.unsubscribe(GAME_ID, ME);
            service.unsubscribe(GAME_ID, ME);
        }).doesNotThrowAnyException();
        assertThat(registry.count(GAME_ID)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-48] 종료 명령 발행이 실패해도 로컬 종료만 하고 예외 없이 끝난다(200)")
    void unsubscribe_publishFails_stillSucceedsAndClosesLocal() {
        service.subscribe(GAME_ID, ME, null);
        doThrow(new ChatPublishException("down", new RuntimeException())).when(producer).sendControl(any());

        assertThatCode(() -> service.unsubscribe(GAME_ID, ME)).doesNotThrowAnyException();

        assertThat(registry.count(GAME_ID)).isZero();
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장은 다른 방의 구독이나 다른 사용자의 구독을 닫지 않는다")
    void unsubscribe_affectsOnlyThatUserInThatRoom() {
        service.subscribe(GAME_ID, 2L, null);

        service.unsubscribe(GAME_ID, ME);
        service.unsubscribe("OTHER", 2L);

        assertThat(registry.count(GAME_ID)).isEqualTo(1);
    }
}
