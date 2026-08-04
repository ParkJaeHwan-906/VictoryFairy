package com.skhynix.domain.record.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BatterRecord}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * DB 전략 관련 결정은 {@link com.skhynix.domain.game.entity.GameTest} javadoc 참고 — FK 제약과
 * {@code (game_id, player_id)} UNIQUE 는 이 테스트로 검증되지 않는다.
 */
class BatterRecordTest {

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
    @DisplayName("정상 케이스: player/game과 모든 스탯 컬럼을 채워 build하면 각 필드가 그대로 보존된다")
    void builder_withAllFields_keepsPlayerGameAndAllStatColumns() {
        // given
        Team home = Team.builder().name("삼성").code("SS").build();
        Team away = Team.builder().name("LG").code("LG").build();
        Player player = Player.builder().team(away).name("김호령").average(0.281).build();
        Game game = newGame(home, away);

        // when
        BatterRecord record = BatterRecord.builder()
                .player(player)
                .game(game)
                .atBats(4)
                .runs(1)
                .hits(2)
                .homeRuns(1)
                .rbi(3)
                .walks(1)
                .strikeouts(1)
                .stolenBases(1)
                .build();

        // then
        assertThat(record.getPlayer()).isSameAs(player);
        assertThat(record.getGame()).isSameAs(game);
        assertThat(record.getAtBats()).isEqualTo(4);
        assertThat(record.getRuns()).isEqualTo(1);
        assertThat(record.getHits()).isEqualTo(2);
        assertThat(record.getHomeRuns()).isEqualTo(1);
        assertThat(record.getRbi()).isEqualTo(3);
        assertThat(record.getWalks()).isEqualTo(1);
        assertThat(record.getStrikeouts()).isEqualTo(1);
        assertThat(record.getStolenBases()).isEqualTo(1);
    }

    @Test
    @DisplayName("null 스탯 허용: API 결측 상황을 가정해 스탯 컬럼을 전부 비우고 build해도 예외 없이 null로 보존된다")
    void builder_withoutStatColumns_buildsWithAllNullStats() {
        // given
        Team home = Team.builder().name("두산").code("OB").build();
        Team away = Team.builder().name("KT").code("KT").build();
        Player player = Player.builder().team(home).name("양의지").average(0.309).build();
        Game game = newGame(home, away);

        // when
        BatterRecord record = BatterRecord.builder()
                .player(player)
                .game(game)
                .build();

        // then
        assertThat(record.getPlayer()).isSameAs(player);
        assertThat(record.getGame()).isSameAs(game);
        assertThat(record.getAtBats()).isNull();
        assertThat(record.getRuns()).isNull();
        assertThat(record.getHits()).isNull();
        assertThat(record.getHomeRuns()).isNull();
        assertThat(record.getRbi()).isNull();
        assertThat(record.getWalks()).isNull();
        assertThat(record.getStrikeouts()).isNull();
        assertThat(record.getStolenBases()).isNull();
    }
}
