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
 * {@link PitcherRecord}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * DB 전략 관련 결정은 {@link com.skhynix.domain.game.entity.GameTest} javadoc 참고 — FK 제약과
 * {@code (game_id, player_id)} UNIQUE 는 이 테스트로 검증되지 않는다.
 */
class PitcherRecordTest {

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
    @DisplayName("정상 케이스: 선발투수(seq=0)에 모든 스탯 컬럼을 채워 build하면 각 필드가 그대로 보존된다")
    void builder_withAllFields_keepsPlayerGameAndAllStatColumns() {
        // given
        Team home = Team.builder().name("삼성").code("SS").build();
        Team away = Team.builder().name("LG").code("LG").build();
        Player player = Player.builder().team(home).name("원태인").average(0).build();
        Game game = newGame(home, away);

        // when
        PitcherRecord record = PitcherRecord.builder()
                .player(player)
                .game(game)
                .seq(0)
                .ipDisplay("6 ⅓")
                .ipOuts(19)
                .battersFaced(25)
                .atBats(23)
                .hits(5)
                .runs(2)
                .earnedRuns(2)
                .homeRuns(1)
                .walksHbp(2)
                .strikeouts(7)
                .build();

        // then
        assertThat(record.getPlayer()).isSameAs(player);
        assertThat(record.getGame()).isSameAs(game);
        assertThat(record.getSeq()).isEqualTo(0);
        assertThat(record.getIpDisplay()).isEqualTo("6 ⅓");
        assertThat(record.getIpOuts()).isEqualTo(19);
        assertThat(record.getBattersFaced()).isEqualTo(25);
        assertThat(record.getAtBats()).isEqualTo(23);
        assertThat(record.getHits()).isEqualTo(5);
        assertThat(record.getRuns()).isEqualTo(2);
        assertThat(record.getEarnedRuns()).isEqualTo(2);
        assertThat(record.getHomeRuns()).isEqualTo(1);
        assertThat(record.getWalksHbp()).isEqualTo(2);
        assertThat(record.getStrikeouts()).isEqualTo(7);
    }

    @Test
    @DisplayName("null 스탯 허용: 구원투수(seq=1)로 스탯 컬럼을 전부 비우고 build해도 예외 없이 null로 보존되며 seq는 여전히 원시 int로 채워진다")
    void builder_withoutStatColumns_buildsWithAllNullStatsAndPrimitiveSeq() {
        // given
        Team home = Team.builder().name("두산").code("OB").build();
        Team away = Team.builder().name("KT").code("KT").build();
        Player player = Player.builder().team(away).name("김택연").average(0).build();
        Game game = newGame(home, away);

        // when
        PitcherRecord record = PitcherRecord.builder()
                .player(player)
                .game(game)
                .seq(1)
                .build();

        // then
        assertThat(record.getPlayer()).isSameAs(player);
        assertThat(record.getGame()).isSameAs(game);
        assertThat(record.getSeq()).isEqualTo(1);
        assertThat(record.getIpDisplay()).isNull();
        assertThat(record.getIpOuts()).isNull();
        assertThat(record.getBattersFaced()).isNull();
        assertThat(record.getAtBats()).isNull();
        assertThat(record.getHits()).isNull();
        assertThat(record.getRuns()).isNull();
        assertThat(record.getEarnedRuns()).isNull();
        assertThat(record.getHomeRuns()).isNull();
        assertThat(record.getWalksHbp()).isNull();
        assertThat(record.getStrikeouts()).isNull();
    }
}
