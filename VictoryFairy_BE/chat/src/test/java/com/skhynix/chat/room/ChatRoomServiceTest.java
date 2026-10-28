package com.skhynix.chat.room;

import static com.skhynix.chat.support.ChatFixtures.NOON_KST_UTC;
import static com.skhynix.chat.support.ChatFixtures.assertBusiness;
import static com.skhynix.chat.support.ChatFixtures.clockAt;
import static com.skhynix.chat.support.ChatFixtures.game;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.chat.room.dto.RoomResponse;
import com.skhynix.chat.room.service.ChatRoomGuard;
import com.skhynix.chat.room.service.ChatRoomService;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatRoomServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 9, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 10, 0, 0);

    @Mock
    private GameRepository gameRepository;
    @Mock
    private ChatRoomGuard roomGuard;

    private ChatRoomService service;

    @BeforeEach
    void setUp() {
        service = new ChatRoomService(gameRepository, roomGuard, clockAt(NOON_KST_UTC));
    }

    @Test
    @DisplayName("[CHAT-GC-26] 목록은 오늘(KST) 반개구간으로 games 를 조회하고 7개 필드로 매핑하며 방 가드(Redis)를 쓰지 않는다")
    void getRooms_readsTodayRangeFromGamesOnly() {
        Game g = game("20261009HTLG0", LocalDateTime.of(2026, 10, 9, 18, 30), "IN_PROGRESS");
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(START, END))
                .willReturn(List.of(g));

        List<RoomResponse> rooms = service.getRooms();

        assertThat(rooms).containsExactly(new RoomResponse("20261009HTLG0", "LG", 1L, "두산", 2L,
                LocalDateTime.of(2026, 10, 9, 18, 30), "IN_PROGRESS"));
        verifyNoInteractions(roomGuard);
    }

    @Test
    @DisplayName("[CHAT-GC-21] 오늘 경기가 없으면 빈 목록이다")
    void getRooms_noGames_returnsEmptyList() {
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of());

        assertThat(service.getRooms()).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-26] naver_game_id 가 없는 경기는 방 경로로 들어올 수 없으므로 목록에서 뺀다")
    void getRooms_excludesGamesWithoutNaverGameId() {
        Game withId = game("20261009HTLG0", LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        Game withoutId = game(null, LocalDateTime.of(2026, 10, 9, 19, 0), "SCHEDULED");
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(any(), any()))
                .willReturn(List.of(withId, withoutId));

        assertThat(service.getRooms()).extracting(RoomResponse::gameId).containsExactly("20261009HTLG0");
    }

    @Test
    @DisplayName("[CHAT-GC-27] 상세는 메타가 이미 있으면 games 의 현재 값(경기 상태)을 읽어 돌려준다")
    void getRoom_metaExists_readsCurrentGameState() {
        Game inProgress = game("20261009HTLG0", LocalDateTime.of(2026, 10, 9, 18, 30), "IN_PROGRESS");
        given(roomGuard.requireOrCreate(any(), any())).willReturn(Optional.empty());
        given(gameRepository.findWithDetailsByNaverGameId("20261009HTLG0")).willReturn(Optional.of(inProgress));

        RoomResponse response = service.getRoom("20261009HTLG0");

        assertThat(response.gameState()).isEqualTo("IN_PROGRESS");
        assertThat(response.gameId()).isEqualTo("20261009HTLG0");
    }

    @Test
    @DisplayName("[CHAT-GC-27] 경기가 진행 중이 되면 재호출 시 gameState 가 IN_PROGRESS 로 바뀐다 — 메타는 상태를 들고 있지 않고 매 요청 games 의 현재 값을 읽는다")
    void getRoom_reflectsGameStateChangeOnNextCall() {
        Game scheduled = game("G1", LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        Game inProgress = game("G1", LocalDateTime.of(2026, 10, 9, 18, 30), "IN_PROGRESS");
        given(roomGuard.requireOrCreate(any(), any())).willReturn(Optional.empty());
        given(gameRepository.findWithDetailsByNaverGameId("G1"))
                .willReturn(Optional.of(scheduled), Optional.of(inProgress));

        assertThat(service.getRoom("G1").gameState()).isEqualTo("SCHEDULED");
        assertThat(service.getRoom("G1").gameState()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("[CHAT-GC-27] 지연 생성 경로에서 가드가 이미 경기를 읽어 왔으면 games 를 다시 조회하지 않는다(요청당 SELECT 1회)")
    void getRoom_lazyCreate_doesNotReadGamesTwice() {
        Game g = game("20261009HTLG0", LocalDateTime.of(2026, 10, 9, 18, 30), "SCHEDULED");
        given(roomGuard.requireOrCreate(any(), any())).willReturn(Optional.of(g));

        RoomResponse response = service.getRoom("20261009HTLG0");

        assertThat(response.gameState()).isEqualTo("SCHEDULED");
        verify(gameRepository, never()).findWithDetailsByNaverGameId(any());
    }

    @Test
    @DisplayName("[CHAT-GC-28] 가드가 404 를 던지면 그대로 전파된다")
    void getRoom_guardThrows404_propagates() {
        given(roomGuard.requireOrCreate(any(), any())).willThrow(new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        assertBusiness(() -> service.getRoom("old"), ErrorCode.CHATROOM_NOT_FOUND);
    }

    @Test
    @DisplayName("[CHAT-GC-28] 메타는 있는데 games 에 행이 사라졌으면 404 이다")
    void getRoom_metaExistsButGameGone_throws404() {
        given(roomGuard.requireOrCreate(any(), any())).willReturn(Optional.empty());
        given(gameRepository.findWithDetailsByNaverGameId("x")).willReturn(Optional.empty());

        assertBusiness(() -> service.getRoom("x"), ErrorCode.CHATROOM_NOT_FOUND);
    }
}
