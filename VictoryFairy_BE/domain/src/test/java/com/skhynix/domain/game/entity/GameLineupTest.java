package com.skhynix.domain.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GameLineup}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * DB 전략 관련 결정은 {@link GameTest} javadoc 참고 — FK 제약과
 * {@code (game_id, player_id)} UNIQUE 는 이 테스트로 검증되지 않는다.
 */
class GameLineupTest {

    private Game newGame(Team home, Team away) {
        return Game.builder()
                .gameDate(LocalDateTime.of(2026, 7, 8, 18, 30))
                .homeTeam(home)
                .awayTeam(away)
                .gameStatus(GameStatus.builder().name("FINISHED").build())
                .naverGameId("20260708LGSS02026")
                .build();
    }

    @Test
    @DisplayName("선발 타자: game/team/player, 타순, 포지션, isStarter=true가 그대로 보존된다")
    void builder_withStartingBatter_keepsOrderPositionAndStarterFlag() {
        // given
        Team home = Team.builder().name("삼성").code("SS").build();
        Team away = Team.builder().name("LG").code("LG").build();
        Player batter = Player.builder().team(away).name("김호령").average(0.281).build();
        Game game = newGame(home, away);

        // when
        GameLineup lineup = GameLineup.builder()
                .game(game)
                .team(away)
                .player(batter)
                .batOrder(1)
                .position("중")
                .isStarter(true)
                .build();

        // then
        assertThat(lineup.getGame()).isSameAs(game);
        assertThat(lineup.getTeam()).isSameAs(away);
        assertThat(lineup.getPlayer()).isSameAs(batter);
        assertThat(lineup.getBatOrder()).isEqualTo(1);
        assertThat(lineup.getPosition()).isEqualTo("중");
        assertThat(lineup.isStarter()).isTrue();
        assertThat(lineup.getDecision()).isNull();
    }

    @Test
    @DisplayName("구원투수: 타순 없이(batOrder=null) decision(W)과 isStarter=false로 build된다")
    void builder_withReliefPitcher_keepsDecisionWithoutBatOrder() {
        // given
        Team home = Team.builder().name("삼성").code("SS").build();
        Team away = Team.builder().name("LG").code("LG").build();
        Player pitcher = Player.builder().team(home).name("김민").average(0).build();

        // when
        GameLineup lineup = GameLineup.builder()
                .game(newGame(home, away))
                .team(home)
                .player(pitcher)
                .position("투")
                .isStarter(false)
                .decision("W")
                .build();

        // then
        assertThat(lineup.getBatOrder()).isNull();
        assertThat(lineup.getPosition()).isEqualTo("투");
        assertThat(lineup.isStarter()).isFalse();
        assertThat(lineup.getDecision()).isEqualTo("W");
    }
}
