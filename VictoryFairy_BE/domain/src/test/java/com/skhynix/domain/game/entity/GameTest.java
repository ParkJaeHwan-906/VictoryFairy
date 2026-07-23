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
        assertThat(game.getGameStatus()).isSameAs(scheduled);
        assertThat(game.getGameStatus().getName()).isEqualTo("SCHEDULED");
        assertThat(game.getHomeTeam()).isSameAs(home);
        assertThat(game.getAwayTeam()).isSameAs(away);
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
}
