package com.skhynix.user.game.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.team.entity.Team;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link GameUpdateService#load}가 승률 변화 문서를 기존 경로 그대로 통과시키는지 증명한다.
 * 요구사항: {@code docs/requirements/user/game-win-rate.md}(USER-GWR-10 ~ 12). 코드 변경 없이 성립해야 하는
 * 계약이라 "DB 재조회 + changed 무가공" 두 가지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class GameUpdateServiceWinRateTest {

    private static final String GAME_ID = "20261013LGHT02026";
    private static final String OBSERVED_AT = "2026-10-13T09:30:00+00:00";

    @Mock
    private GameRepository gameRepository;

    @InjectMocks
    private GameUpdateService service;

    private static Team team(long id, String name) {
        Team team = Team.builder().name(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static Game game(String state, String home, String away) {
        Game game = Game.builder()
                .naverGameId(GAME_ID)
                .gameDate(LocalDateTime.of(2026, 10, 13, 18, 30))
                .homeTeam(team(3L, "LG"))
                .awayTeam(team(7L, "KIA"))
                .homeScore(3)
                .awayScore(5)
                .gameStatus(GameStatus.builder().name(state).build())
                .currentInning(5)
                .build();
        ReflectionTestUtils.setField(game, "homeWinRate", home == null ? null : new BigDecimal(home));
        ReflectionTestUtils.setField(game, "awayWinRate", away == null ? null : new BigDecimal(away));
        return game;
    }

    @Test
    @DisplayName("[USER-GWR-10] 승률만 바뀐 문서도 DB 를 다시 읽은 값(41.2/58.8)을 담은 game-update 가 만들어진다")
    void load_winRateOnlyChange_usesFreshDbValues() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("IN_PROGRESS", "41.2", "58.8")));

        Optional<GameUpdateEvent> event = service.load(
                new GameStateEventDocument(GAME_ID, List.of("winRate"), OBSERVED_AT));

        assertThat(event).isPresent();
        assertThat(event.get().game().homeTeamWinRate()).isEqualTo(41.2);
        assertThat(event.get().game().awayTeamWinRate()).isEqualTo(58.8);
        assertThat(event.get().game().homeTeamScore()).isEqualTo(3);
        assertThat(event.get().observedAt()).isEqualTo(OBSERVED_AT);
    }

    @Test
    @DisplayName("[USER-GWR-10] 첫 유효값(NULL -> 35.8) 문서도 같은 방식으로 값이 담긴 game-update 가 된다")
    void load_firstValidWinRate_producesUpdateWithValue() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("IN_PROGRESS", "35.8", "64.2")));

        Optional<GameUpdateEvent> event = service.load(
                new GameStateEventDocument(GAME_ID, List.of("winRate"), OBSERVED_AT));

        assertThat(event).isPresent();
        assertThat(event.get().game().homeTeamWinRate()).isEqualTo(35.8);
        assertThat(event.get().game().awayTeamWinRate()).isEqualTo(64.2);
    }

    @Test
    @DisplayName("[USER-GWR-11] changed=[winRate] 는 걸러지지 않고 이벤트 changed 에 그대로 실린다")
    void load_changedWinRateOnly_passedThrough() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("IN_PROGRESS", "41.2", "58.8")));

        GameUpdateEvent event = service.load(new GameStateEventDocument(GAME_ID, List.of("winRate"), OBSERVED_AT))
                .orElseThrow();

        assertThat(event.changed()).containsExactly("winRate");
    }

    @Test
    @DisplayName("[USER-GWR-11] changed=[homeScore, winRate] 는 이벤트 1건에 두 토큰이 순서 그대로 실린다")
    void load_changedMixed_passedThroughAsOneEvent() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("IN_PROGRESS", "41.2", "58.8")));

        Optional<GameUpdateEvent> event = service.load(
                new GameStateEventDocument(GAME_ID, List.of("homeScore", "winRate"), OBSERVED_AT));

        assertThat(event).isPresent();
        assertThat(event.get().changed()).containsExactly("homeScore", "winRate");
    }

    @Test
    @DisplayName("[USER-GWR-12] changed 에 winRate 가 있어도 승률이 null 이면 이벤트를 버리지 않고 null/null 로 보낸다"
            + "(종료: inning,status,winRate)")
    void load_winRateMaskedToNull_stillProducesEvent() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("FINISHED", null, null)));

        Optional<GameUpdateEvent> event = service.load(
                new GameStateEventDocument(GAME_ID, List.of("inning", "status", "winRate"), OBSERVED_AT));

        assertThat(event).isPresent();
        assertThat(event.get().game().gameState()).isEqualTo("FINISHED");
        assertThat(event.get().game().homeTeamWinRate()).isNull();
        assertThat(event.get().game().awayTeamWinRate()).isNull();
        assertThat(event.get().changed()).containsExactly("inning", "status", "winRate");
    }

    @Test
    @DisplayName("[USER-GWR-12] 종료 경기에 DB 승률이 남아 있어도(NULL 쓰기 실패) game-update 는 null/null 로 나간다")
    void load_finishedWithStaleDbValues_masksInEvent() {
        given(gameRepository.findWithDetailsByNaverGameId(GAME_ID))
                .willReturn(Optional.of(game("FINISHED", "98.7", "1.3")));

        GameUpdateEvent event = service.load(
                new GameStateEventDocument(GAME_ID, List.of("status", "winRate"), OBSERVED_AT)).orElseThrow();

        assertThat(event.game().homeTeamWinRate()).isNull();
        assertThat(event.game().awayTeamWinRate()).isNull();
    }
}
