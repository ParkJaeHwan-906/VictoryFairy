package com.skhynix.chat.message;

import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.message.dto.HistoryResponse;
import com.skhynix.chat.message.service.ChatHistoryService;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.redis.ChatStreamEntry;
import com.skhynix.chat.shared.redis.ChatStreamReader;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserBlockRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatHistoryServiceTest {

    private static final String GAME_ID = "G1";
    private static final Long ME = 1L;

    @Mock
    private ChatRoomGuard roomGuard;
    @Mock
    private ChatStreamReader streamReader;
    @Mock
    private UserBlockRepository userBlockRepository;

    private ChatHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryService(roomGuard, streamReader, userBlockRepository);
        given(userBlockRepository.findRelatedAccountIds(ME)).willReturn(Set.of());
        given(streamReader.blinded(anyString(), anyCollection())).willReturn(Set.of());
    }

    /** 최신순(내림차순) 엔트리. msgId = from, from-1, ... */
    private static List<ChatStreamEntry> descending(long from, int count, Long senderId) {
        List<ChatStreamEntry> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = from - i;
            list.add(new ChatStreamEntry(id, senderId, "닉" + id, "OB", null, "내용" + id, "2026-10-09T19:00:00.000+09:00"));
        }
        return list;
    }

    @Test
    @DisplayName("[CHAT-GC-68] 첫 페이지는 cursor 없이(최신부터) 30건을 요청하고 최신순 항목과 nextCursor·hasNext 를 돌려준다")
    void getHistory_firstPage_readsLatestThirty() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(7129, 30, 2L));

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response.messages()).hasSize(30);
        assertThat(response.messages().get(0).msgId()).isEqualTo(7129L);
        assertThat(response.messages().get(29).msgId()).isEqualTo(7100L);
        assertThat(response.nextCursor()).isEqualTo(7100L);
        assertThat(response.hasNext()).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-69] cursor 를 주면 그 값을 그대로 리더에 넘겨 '그 offset 미포함' 더 오래된 쪽을 읽는다")
    void getHistory_withCursor_passesCursorToReader() {
        given(streamReader.latest(GAME_ID, 7100L, 30)).willReturn(descending(7099, 5, 2L));

        HistoryResponse response = service.getHistory(GAME_ID, 7100L, ME);

        assertThat(response.messages().get(0).msgId()).isLessThan(7100L);
        verify(streamReader).latest(GAME_ID, 7100L, 30);
    }

    @Test
    @DisplayName("[CHAT-GC-70] 원본이 30건 미만이면 hasNext=false 이고 nextCursor 는 원본 마지막(가장 작은) msgId 이다")
    void getHistory_underPageSize_hasNextFalse() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(50, 29, 2L));

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isEqualTo(22L);
    }

    @Test
    @DisplayName("[CHAT-GC-70] 원본이 정확히 30건이면 hasNext=true 이다(경계)")
    void getHistory_exactlyPageSize_hasNextTrue() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(100, 30, 2L));

        assertThat(service.getHistory(GAME_ID, null, ME).hasNext()).isTrue();
    }

    @Test
    @DisplayName("[CHAT-GC-70] 원본이 0건이면 messages [] · nextCursor null · hasNext false 이고 차단 목록 조회도 하지 않는다")
    void getHistory_emptyStream_returnsEmptyPage() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(List.of());

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response).isEqualTo(new HistoryResponse(List.of(), null, false));
        verify(userBlockRepository, never()).findRelatedAccountIds(anyLong());
    }

    @Test
    @DisplayName("[CHAT-GC-70] blind·차단으로 30건 중 10건이 걸러져도 hasNext=true, nextCursor 는 원본 30번째의 msgId 이다")
    void getHistory_filteredPage_cursorAndHasNextFollowRawPage() {
        // 100..71 중 blind 5건(100..96), 차단 상대(3L) 메시지 5건(95..91)이 걸러진다
        List<ChatStreamEntry> raw = new ArrayList<>();
        for (ChatStreamEntry e : descending(100, 30, 2L)) {
            boolean blockedSender = e.msgId() <= 95 && e.msgId() >= 91;
            raw.add(new ChatStreamEntry(e.msgId(), blockedSender ? 3L : 2L, e.senderNickname(), e.teamCode(),
                    e.profileImgUrl(), e.content(), e.sentAt()));
        }
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(raw);
        given(streamReader.blinded(eq(GAME_ID), anyCollection())).willReturn(Set.of(100L, 99L, 98L, 97L, 96L));
        given(userBlockRepository.findRelatedAccountIds(ME)).willReturn(Set.of(3L));

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response.messages()).hasSize(20);
        assertThat(response.messages()).extracting(ChatMessageView::msgId)
                .doesNotContain(100L, 99L, 98L, 97L, 96L, 95L, 94L, 93L, 92L, 91L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(71L);
    }

    @Test
    @DisplayName("[CHAT-GC-71] blind 집합에 든 msgId 는 응답에서 제외되고 조회는 원본 페이지의 msgId 들로 한 번 한다")
    void getHistory_blindedMessages_areExcluded() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(10, 3, 2L));
        given(streamReader.blinded(eq(GAME_ID), anyCollection())).willReturn(Set.of(9L));

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response.messages()).extracting(ChatMessageView::msgId).containsExactly(10L, 8L);
        verify(streamReader, times(1)).blinded(eq(GAME_ID), eq(List.of(10L, 9L, 8L)));
    }

    @Test
    @DisplayName("[CHAT-GC-72] 차단 관계(양방향) 계정의 메시지는 제외하고, 차단 조회는 페이지 크기와 무관하게 1회이다")
    void getHistory_blockedSenders_areExcludedWithSingleLookup() {
        List<ChatStreamEntry> raw = new ArrayList<>();
        raw.addAll(descending(30, 10, 5L));
        raw.addAll(descending(20, 10, 6L));
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(raw);
        given(userBlockRepository.findRelatedAccountIds(ME)).willReturn(Set.of(5L));

        HistoryResponse response = service.getHistory(GAME_ID, null, ME);

        assertThat(response.messages()).hasSize(10);
        verify(userBlockRepository, times(1)).findRelatedAccountIds(ME);
    }

    @Test
    @DisplayName("[CHAT-GC-72] 발신자 정보가 없는(senderId=null) 엔트리는 차단 필터에 걸리지 않는다")
    void getHistory_nullSender_isNotFiltered() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(5, 2, null));
        given(userBlockRepository.findRelatedAccountIds(ME)).willReturn(Set.of(5L));

        assertThat(service.getHistory(GAME_ID, null, ME).messages()).hasSize(2);
    }

    @Test
    @DisplayName("[CHAT-GC-73] 항목은 6개 필드이고 계정 내부 id(senderId)가 없다")
    void getHistory_itemFields() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(7, 1, 2L));

        ChatMessageView item = service.getHistory(GAME_ID, null, ME).messages().get(0);

        assertThat(item).isEqualTo(new ChatMessageView(7L, "내용7", "닉7", "OB", null, "2026-10-09T19:00:00.000+09:00"));
        assertThat(ChatMessageView.class.getRecordComponents()).hasSize(6);
    }

    @Test
    @DisplayName("[CHAT-GC-64] 조회 경로는 계정 테이블에 의존하지 않는다(발신자 표시 정보는 스냅샷 그대로) — 서비스 의존성에 계정 리포지토리가 없다")
    void service_hasNoAccountRepositoryDependency() {
        assertThat(Arrays.stream(ChatHistoryService.class.getDeclaredConstructors()[0].getParameterTypes())
                .map(Class::getSimpleName))
                .containsExactlyInAnyOrder("ChatRoomGuard", "ChatStreamReader", "UserBlockRepository");
    }

    @Test
    @DisplayName("[CHAT-GC-76] 히스토리는 history-writer 가 적재한 Redis Stream 만 읽는다 — Kafka 프로듀서·컨슈머에 의존하지 않으므로 202 직후의 메시지는 적재 랙만큼 아직 없을 수 있다(계약)")
    void service_readsOnlyTheStreamCopy() {
        assertThat(Arrays.stream(ChatHistoryService.class.getDeclaredConstructors()[0].getParameterTypes())
                .map(Class::getName))
                .noneMatch(name -> name.contains("kafka") || name.contains("Kafka"));
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(List.of());

        // Stream 에 아직 없으면(적재 전) 빈 페이지다 — 에러도 Kafka 조회도 아니다
        assertThat(service.getHistory(GAME_ID, null, ME).messages()).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-24] 방이 없으면 404 이고 Stream 도 차단 목록도 읽지 않는다")
    void getHistory_roomMissing_is404() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND)).when(roomGuard).requireExisting(GAME_ID);

        assertBusiness(() -> service.getHistory(GAME_ID, null, ME), ErrorCode.CHATROOM_NOT_FOUND);

        verifyNoInteractions(streamReader, userBlockRepository);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 히스토리는 방 메타를 지연 생성하는 requireOrCreate 를 쓰지 않고 requireExisting 만 쓴다")
    void getHistory_usesRequireExistingNotRequireOrCreate() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(List.of());

        service.getHistory(GAME_ID, null, ME);

        verify(roomGuard).requireExisting(GAME_ID);
        verify(roomGuard, never()).requireOrCreate(anyString());
        verify(roomGuard, never()).requireOrCreate(anyString(), any());
    }

    @Test
    @DisplayName("[CHAT-GC-75] XREVRANGE 가 실패하면 빈 배열 200 이 아니라 503 CHAT_BROKER_UNAVAILABLE 이다")
    void getHistory_streamReadFails_is503() {
        given(streamReader.latest(anyString(), any(), anyInt())).willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> service.getHistory(GAME_ID, null, ME), ErrorCode.CHAT_BROKER_UNAVAILABLE);
    }

    @Test
    @DisplayName("[CHAT-GC-75] blind 집합 조회가 실패해도 503 이다")
    void getHistory_blindLookupFails_is503() {
        given(streamReader.latest(GAME_ID, null, 30)).willReturn(descending(5, 2, 2L));
        given(streamReader.blinded(anyString(), anyCollection())).willThrow(new QueryTimeoutException("timeout"));

        assertBusiness(() -> service.getHistory(GAME_ID, null, ME), ErrorCode.CHAT_BROKER_UNAVAILABLE);
    }
}
