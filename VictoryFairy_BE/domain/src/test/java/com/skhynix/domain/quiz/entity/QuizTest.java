package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Quiz}의 Builder 필드 배선과 출제 대상 판정(5분류 — {@code isPlayerQuiz}/{@code isTeamQuiz}/
 * {@code isMatchupQuiz}/{@code isGameQuiz}/{@code isGeneralQuiz})만 검증하는 순수 단위 테스트
 * (Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략</b>: {@code domain} 모듈에 {@code @DataJpaTest} 실행에 필요한 H2/Testcontainers/구동 중인
 * MySQL 이 없어({@link com.skhynix.domain.stadium.entity.StadiumTest} javadoc 참고) 저장·조회 라운드트립은
 * 다루지 않는다. {@code team_id}/{@code player_id}가 실제로 nullable 로 생성되는지, FK 제약이 걸리는지는
 * 이 테스트로 검증되지 않는다.
 */
class QuizTest {

    private QuizType newQuizType(String name) {
        return QuizType.builder().name(name).build();
    }

    private Team newTeam(String name) {
        return Team.builder().name(name).build();
    }

    private Player newPlayer(Team team, String name) {
        return Player.builder().team(team).name(name).build();
    }

    @Test
    @DisplayName("Builder로 생성하면 필드가 동일 인스턴스로 배선된다")
    void builder_wiresFieldsToSameInstance() {
        // given
        QuizType quizType = newQuizType("객관식");
        Team team = newTeam("두산");
        Player player = newPlayer(team, "김재환");

        // when
        Quiz quiz = Quiz.builder()
                .quizType(quizType)
                .team(team)
                .player(player)
                .content("이 선수의 2026 시즌 홈런 수는?")
                .answer(2)
                .point(1.5)
                .build();

        // then
        assertThat(quiz.getQuizType()).isSameAs(quizType);
        assertThat(quiz.getTeam()).isSameAs(team);
        assertThat(quiz.getPlayer()).isSameAs(player);
        assertThat(quiz.getContent()).isEqualTo("이 선수의 2026 시즌 홈런 수는?");
        assertThat(quiz.getAnswer()).isEqualTo(2);
        assertThat(quiz.getPoint()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("team·player가 모두 있으면 특정 선수에 대한 문제로 판정된다")
    void isPlayerQuiz_whenBothTeamAndPlayerPresent() {
        // given
        Team team = newTeam("LG");
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .team(team)
                .player(newPlayer(team, "오지환"))
                .content("선수 문제")
                .answer(1)
                .build();

        // then
        assertThat(quiz.isPlayerQuiz()).isTrue();
        assertThat(quiz.isTeamQuiz()).isFalse();
        assertThat(quiz.isGeneralQuiz()).isFalse();
    }

    @Test
    @DisplayName("team만 있으면 특정 구단에 대한 문제로 판정된다")
    void isTeamQuiz_whenOnlyTeamPresent() {
        // given
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("O/X"))
                .team(newTeam("KIA"))
                .content("구단 문제")
                .answer(1)
                .build();

        // then
        assertThat(quiz.isTeamQuiz()).isTrue();
        assertThat(quiz.isPlayerQuiz()).isFalse();
        assertThat(quiz.isGeneralQuiz()).isFalse();
    }

    @Test
    @DisplayName("team·player가 모두 없으면 야구 도메인 자체의 문제로 판정된다")
    void isGeneralQuiz_whenNeitherTeamNorPlayerPresent() {
        // given
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("O/X"))
                .content("인필드 플라이는 주자 1·2루에서도 선언된다")
                .answer(1)
                .build();

        // then
        assertThat(quiz.isGeneralQuiz()).isTrue();
        assertThat(quiz.isTeamQuiz()).isFalse();
        assertThat(quiz.isPlayerQuiz()).isFalse();
        assertThat(quiz.getTeam()).isNull();
        assertThat(quiz.getPlayer()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-PBQ-5] point·bq·externalId·difficulty는 지정하지 않으면 null로 남는다"
            + "(사람이 쓴 퀴즈 경로 — 두 배점 축이 서로 독립적으로 null 가능하다)")
    void optionalFields_areNullWhenNotGiven() {
        // given
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .content("점수 미지정 문제")
                .answer(3)
                .build();

        // then
        assertThat(quiz.getPoint()).isNull();
        assertThat(quiz.getBq()).isNull();
        assertThat(quiz.getExternalId()).isNull();
        assertThat(quiz.getDifficulty()).isNull();
        assertThat(quiz.getTemplateId()).isNull();
    }

    @Test
    @DisplayName("[QUIZ-PBQ-5] builder(bq=3)으로 생성하면 getBq()가 3을 반환한다 — point와 별개 축이라 "
            + "point가 null이어도 bq만 채울 수 있다")
    void builder_wiresBqIndependentlyOfPoint() {
        // when
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .content("bq만 있는 문제")
                .answer(0)
                .bq(3)
                .build();

        // then
        assertThat(quiz.getBq()).isEqualTo(3);
        assertThat(quiz.getPoint()).isNull();
    }

    @Test
    @DisplayName("team·opponentTeam이 모두 있으면 맞대결 문제로 판정된다(구단 문제 아님)")
    void isMatchupQuiz_whenBothTeamsPresent() {
        // given — "올해 한화는 KT와의 상대전적에서 우위다?" 형태(quiz-candidates 실데이터 유형)
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .team(newTeam("한화"))
                .opponentTeam(newTeam("KT"))
                .content("올해 한화는 KT와의 상대전적에서 우위다?")
                .answer(1)
                .build();

        // then
        assertThat(quiz.isMatchupQuiz()).isTrue();
        assertThat(quiz.isTeamQuiz()).isFalse();
        assertThat(quiz.isPlayerQuiz()).isFalse();
        assertThat(quiz.isGeneralQuiz()).isFalse();
    }

    @Test
    @DisplayName("game이 있으면 특정 경기 문제이면서, 로더가 채운 홈/원정으로 맞대결 판정도 함께 성립한다")
    void isGameQuiz_whenGamePresent() {
        // given — 로더 불변식: game 이 있으면 team(홈)·opponentTeam(원정)을 함께 채운다
        Team home = newTeam("LG");
        Team away = newTeam("SSG");
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 4, 18, 30))
                .homeTeam(home)
                .awayTeam(away)
                .gameStatus(GameStatus.builder().name("FINISHED").build())
                .naverGameId("20260804SKLG02026")
                .build();
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .team(home)
                .opponentTeam(away)
                .game(game)
                .content("8/4 LG-SSG 경기 승리투수는?")
                .answer(0)
                .build();

        // then
        assertThat(quiz.isGameQuiz()).isTrue();
        assertThat(quiz.isMatchupQuiz()).isTrue();
        assertThat(quiz.isGeneralQuiz()).isFalse();
    }

    @Test
    @DisplayName("적재 메타(externalId·quizDate·difficulty·templateId)가 Builder로 배선된다")
    void builder_wiresIngestMetadata() {
        // given
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .content("강백호가 FA로 새로 합류한 팀은?")
                .answer(0)
                .point(80.0)
                .externalId("QZ-20260807-001")
                .quizDate(LocalDate.of(2026, 8, 7))
                .difficulty("HARD")
                .templateId("CAREER_PATH")
                .build();

        // then
        assertThat(quiz.getExternalId()).isEqualTo("QZ-20260807-001");
        assertThat(quiz.getQuizDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(quiz.getDifficulty()).isEqualTo("HARD");
        assertThat(quiz.getTemplateId()).isEqualTo("CAREER_PATH");
        assertThat(quiz.getPoint()).isEqualTo(80.0);
    }
}
