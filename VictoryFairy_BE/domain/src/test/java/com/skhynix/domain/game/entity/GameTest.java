package com.skhynix.domain.game.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.stadium.entity.Stadium;
import com.skhynix.domain.team.entity.Team;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Game}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code domain} 모듈에 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers/구동 중인 MySQL이 전혀 없어(자세한 사유는
 * {@link com.skhynix.domain.stadium.entity.StadiumTest} javadoc, 최종 보고 참고) 아래는
 * 실제 저장·조회 라운드트립이 아니라 Builder가 각 필드를 올바른 값으로 채우는지만 확인한다.
 * 즉 FK 제약, {@code stadium_id}/{@code home_score}/{@code away_score}의 nullable 컬럼 매핑,
 * {@link GameStatus}가 {@code game_status_id} FK로 실제 저장·복원되는지는 이 테스트로 검증되지 않는다.
 * {@link GameStatus}는 코드 테이블 엔티티이므로 아래에서는 영속화되지 않은 인스턴스를 그대로 배선한다.
 */
class GameTest {

    private Team newTeam(String name) {
        return Team.builder().name(name).build();
    }

    private Stadium newStadium(String name) {
        return Stadium.builder().name(name).build();
    }

    private GameStatus newGameStatus(String name) {
        return GameStatus.builder().name(name).build();
    }

    @Test
    @DisplayName("정상 케이스: home/away Team, Stadium, 점수, FINISHED 상태를 채워 build하면 모든 필드가 그대로 보존된다")
    void builder_withAllFields_keepsHomeAwayStadiumScoresAndGameStatus() {
        // given
        Team home = newTeam("두산 베어스");
        Team away = newTeam("LG 트윈스");
        Stadium stadium = newStadium("잠실야구장");
        GameStatus finished = newGameStatus("FINISHED");
        LocalDateTime gameDate = LocalDateTime.of(2026, 7, 17, 18, 30);

        // when
        Game game = Game.builder()
                .gameDate(gameDate)
                .homeTeam(home)
                .awayTeam(away)
                .stadium(stadium)
                .homeScore(5)
                .awayScore(3)
                .gameStatus(finished)
                .build();

        // then
        assertThat(game.getGameDate()).isEqualTo(gameDate);
        assertThat(game.getHomeTeam()).isSameAs(home);
        assertThat(game.getAwayTeam()).isSameAs(away);
        assertThat(game.getStadium()).isSameAs(stadium);
        assertThat(game.getHomeScore()).isEqualTo(5);
        assertThat(game.getAwayScore()).isEqualTo(3);
        assertThat(game.getGameStatus()).isSameAs(finished);
        assertThat(game.getGameStatus().getName()).isEqualTo("FINISHED");
    }

    @Test
    @DisplayName("nullable 필드: stadium/homeScore/awayScore를 채우지 않고 SCHEDULED로 build해도 예외 없이 null로 보존된다")
    void builder_withoutNullableFields_buildsWithNullStadiumAndScores() {
        // given
        Team home = newTeam("삼성 라이온즈");
        Team away = newTeam("KIA 타이거즈");
        GameStatus scheduled = newGameStatus("SCHEDULED");

        // when
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 1, 18, 30))
                .homeTeam(home)
                .awayTeam(away)
                .gameStatus(scheduled)
                .build();

        // then
        assertThat(game.getStadium()).isNull();
        assertThat(game.getHomeScore()).isNull();
        assertThat(game.getAwayScore()).isNull();
        assertThat(game.getCancelReason()).isNull();
        assertThat(game.getGameStatus()).isSameAs(scheduled);
        assertThat(game.getGameStatus().getName()).isEqualTo("SCHEDULED");
        assertThat(game.getHomeTeam()).isSameAs(home);
        assertThat(game.getAwayTeam()).isSameAs(away);
    }

    @Test
    @DisplayName("CANCELED 경기는 cancelReason(폭염취소)을 담고, 취소 경기의 0-0 껍데기 대신 점수는 null로 남는다")
    void builder_withCancelReason_keepsReasonAndLeavesScoresNull() {
        // given
        GameStatus canceled = newGameStatus("CANCELED");

        // when
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 9, 18, 0))
                .homeTeam(newTeam("LG 트윈스"))
                .awayTeam(newTeam("KIA 타이거즈"))
                .gameStatus(canceled)
                .cancelReason("폭염취소")
                .build();

        // then
        assertThat(game.getCancelReason()).isEqualTo("폭염취소");
        assertThat(game.getGameStatus().getName()).isEqualTo("CANCELED");
        assertThat(game.getHomeScore()).isNull();
        assertThat(game.getAwayScore()).isNull();
    }

    @Test
    @DisplayName("DRAW 상태로 build하면 getGameStatus()가 그 GameStatus를 그대로 반환한다(인메모리 배선만 확인, 영속화 형식은 미검증)")
    void builder_withDrawGameStatus_keepsDrawGameStatus() {
        // given
        GameStatus draw = newGameStatus("DRAW");

        // when
        Game game = Game.builder()
                .gameDate(LocalDateTime.now())
                .homeTeam(newTeam("한화 이글스"))
                .awayTeam(newTeam("롯데 자이언츠"))
                .gameStatus(draw)
                .build();

        // then
        assertThat(game.getGameStatus()).isSameAs(draw);
        assertThat(game.getGameStatus().getName()).isEqualTo("DRAW");
    }

    @Test
    @DisplayName("IN_PROGRESS 경기: currentInning/inningHalf를 채워 build하면 그대로 보존된다")
    void builder_withCurrentInningAndInningHalf_keepsBothFields() {
        // given
        GameStatus inProgress = newGameStatus("IN_PROGRESS");

        // when
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 11, 18, 30))
                .homeTeam(newTeam("SSG 랜더스"))
                .awayTeam(newTeam("NC 다이노스"))
                .gameStatus(inProgress)
                .currentInning(7)
                .inningHalf(InningHalf.BOTTOM)
                .build();

        // then
        assertThat(game.getCurrentInning()).isEqualTo(7);
        assertThat(game.getInningHalf()).isEqualTo(InningHalf.BOTTOM);
    }

    @Test
    @DisplayName("진행 중이 아닌 경기(예: SCHEDULED): currentInning/inningHalf를 지정하지 않고 build하면 "
            + "둘 다 null로 남는다")
    void builder_withoutInningFields_leavesCurrentInningAndInningHalfNull() {
        // given
        GameStatus scheduled = newGameStatus("SCHEDULED");

        // when
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 12, 18, 30))
                .homeTeam(newTeam("키움 히어로즈"))
                .awayTeam(newTeam("KT 위즈"))
                .gameStatus(scheduled)
                .build();

        // then
        assertThat(game.getCurrentInning()).isNull();
        assertThat(game.getInningHalf()).isNull();
    }

    @Test
    @DisplayName("[회귀][AC-SUB-28-1,28-2,28-3] lastInning은 읽기 전용 매핑이라 Builder 파라미터가 "
            + "없다 — 다른 필드를 전부 채워 build해도 getLastInning()은 항상 null이다(이 앱은 값을 "
            + "만들지 않고 py-collector가 채운 값을 읽기만 한다)")
    void builder_hasNoLastInningParameter_alwaysLeavesItNull() {
        // given
        GameStatus finished = newGameStatus("FINISHED");

        // when
        // ⚠ Game.builder()에는 애초에 .lastInning(...) 메서드 자체가 없다 — 호출하려 하면
        //   컴파일이 안 된다(진짜 방지선). 아래 단언은 그 사실이 실제로 지켜지고 있음을
        //   런타임에서도 보여주는 확인이며, 누군가 lastInning을 Builder 파라미터로 옮기면(회귀)
        //   이 테스트를 그 새 builder 메서드를 호출하도록 고쳐야 함을 리뷰에서 알아챌 신호가 된다.
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 13, 18, 30))
                .homeTeam(newTeam("두산 베어스"))
                .awayTeam(newTeam("LG 트윈스"))
                .homeScore(5)
                .awayScore(3)
                .gameStatus(finished)
                .currentInning(null)
                .build();

        // then
        assertThat(game.getLastInning()).isNull();
    }

    @Test
    @DisplayName("[회귀] InningHalf의 ORDINAL 저장 순서는 TOP=0, BOTTOM=1로 고정된다 — "
            + "선언 순서가 바뀌면 DB에 이미 저장된 값의 의미가 조용히 뒤집히므로 이 테스트가 그 변경을 막는다")
    void inningHalf_ordinalOrder_isFixedTopZeroBottomOne() {
        // then
        assertThat(InningHalf.TOP.ordinal()).isEqualTo(0);
        assertThat(InningHalf.BOTTOM.ordinal()).isEqualTo(1);
        assertThat(InningHalf.values()).containsExactly(InningHalf.TOP, InningHalf.BOTTOM);
    }
}
