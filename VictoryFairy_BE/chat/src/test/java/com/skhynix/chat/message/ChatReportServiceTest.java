package com.skhynix.chat.message;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.message.service.ChatReportService;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.kafka.BlindTombstone;
import com.skhynix.chat.shared.kafka.ChatKafkaProducer;
import com.skhynix.chat.shared.kafka.ChatPublishException;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatReportServiceTest {

    private static final String GAME_ID = "G1";
    private static final long MSG_ID = 4200L;
    private static final Long REPORTER = 1L;
    private static final Long AUTHOR = 2L;

    @Mock
    private ChatRoomGuard roomGuard;
    @Mock
    private ChatStreamReader streamReader;
    @Mock
    private ChatKafkaProducer producer;

    private ChatReportService service;

    @BeforeEach
    void setUp() {
        service = new ChatReportService(roomGuard, streamReader, producer);
        given(streamReader.find(GAME_ID, MSG_ID))
                .willReturn(Optional.of(new ChatStreamEntry(MSG_ID, AUTHOR, "닉", "OB", null, "내용", "t")));
    }

    @Test
    @DisplayName("[CHAT-GC-77] 타인의 메시지를 신고하면 blind 툼스톤 {gameId, msgId} 를 발행한다")
    void report_otherUsersMessage_publishesBlindTombstone() {
        service.report(GAME_ID, MSG_ID, REPORTER);

        verify(producer).sendControl(BlindTombstone.of(GAME_ID, MSG_ID));
    }

    @Test
    @DisplayName("[CHAT-GC-78] 판정 순서는 방 메타 확인 → Stream 엔트리 조회 → 툼스톤 발행이다")
    void report_followsContractOrder() {
        service.report(GAME_ID, MSG_ID, REPORTER);

        InOrder order = inOrder(roomGuard, streamReader, producer);
        order.verify(roomGuard).requireExisting(GAME_ID);
        order.verify(streamReader).find(GAME_ID, MSG_ID);
        order.verify(producer).sendControl(any());
    }

    @Test
    @DisplayName("[CHAT-GC-78] 없는 방 + 본인 msgId 는 404 CHATROOM_NOT_FOUND 이다(방 확인이 본인 판정보다 먼저)")
    void report_missingRoom_is404BeforeAnythingElse() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND)).when(roomGuard).requireExisting(GAME_ID);
        given(streamReader.find(GAME_ID, MSG_ID))
                .willReturn(Optional.of(new ChatStreamEntry(MSG_ID, REPORTER, "닉", null, null, "내용", "t")));

        assertBusiness(() -> service.report(GAME_ID, MSG_ID, REPORTER), ErrorCode.CHATROOM_NOT_FOUND);

        verifyNoInteractions(streamReader, producer);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 신고는 방 메타를 지연 생성하지 않는다 — requireOrCreate 를 부르지 않는다")
    void report_doesNotLazilyCreateRoom() {
        service.report(GAME_ID, MSG_ID, REPORTER);

        verify(roomGuard, never()).requireOrCreate(anyString());
        verify(roomGuard, never()).requireOrCreate(anyString(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-79] Stream 에 엔트리가 없으면 404 CHAT_MESSAGE_NOT_FOUND 이고 발행하지 않는다")
    void report_entryMissing_is404NoPublish() {
        given(streamReader.find(GAME_ID, 999L)).willReturn(Optional.empty());

        assertBusiness(() -> service.report(GAME_ID, 999L, REPORTER), ErrorCode.CHAT_MESSAGE_NOT_FOUND);

        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("[CHAT-GC-80] 본인 메시지를 신고하면 403 SELF_REPORT_NOT_ALLOWED 이고 발행하지 않는다")
    void report_ownMessage_is403NoPublish() {
        assertBusiness(() -> service.report(GAME_ID, MSG_ID, AUTHOR), ErrorCode.SELF_REPORT_NOT_ALLOWED);

        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("[CHAT-GC-81] 이미 신고된(blind) 메시지를 다시 신고해도 예외 없이 끝나고 툼스톤을 다시 발행한다(멱등)")
    void report_sameMessageTwice_bothSucceed() {
        service.report(GAME_ID, MSG_ID, REPORTER);
        service.report(GAME_ID, MSG_ID, REPORTER);

        verify(producer, times(2)).sendControl(BlindTombstone.of(GAME_ID, MSG_ID));
    }

    @Test
    @DisplayName("[CHAT-GC-82] 툼스톤 발행이 실패하면 200 으로 삼키지 않고 503 CHAT_BROKER_UNAVAILABLE 이다")
    void report_kafkaFailure_is503() {
        doThrow(new ChatPublishException("down", new RuntimeException())).when(producer).sendControl(any());

        assertBusiness(() -> service.report(GAME_ID, MSG_ID, REPORTER), ErrorCode.CHAT_BROKER_UNAVAILABLE);
    }

    @Test
    @DisplayName("[CHAT-GC-75] Stream 조회 중 Redis 가 실패하면 503 이고 발행하지 않는다")
    void report_redisFailure_is503() {
        given(streamReader.find(anyString(), anyLong())).willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> service.report(GAME_ID, MSG_ID, REPORTER), ErrorCode.CHAT_BROKER_UNAVAILABLE);

        verifyNoInteractions(producer);
    }

    @Test
    @DisplayName("[CHAT-GC-83] 신고자·사유·횟수를 저장할 협력 객체가 없다 — 서비스 의존성은 방 가드·Stream 리더·프로듀서뿐이다")
    void service_storesNothingAboutReporter() {
        assertThat(Arrays.stream(ChatReportService.class.getDeclaredConstructors()[0].getParameterTypes())
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder("ChatRoomGuard", "ChatStreamReader", "ChatKafkaProducer");
    }
}
