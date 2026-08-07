package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Quiz}의 Builder 필드 배선과 출제 대상 판정({@code isPlayerQuiz}/{@code isTeamQuiz}/
 * {@code isGeneralQuiz})만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
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
                .score(1.5)
                .build();

        // then
        assertThat(quiz.getQuizType()).isSameAs(quizType);
        assertThat(quiz.getTeam()).isSameAs(team);
        assertThat(quiz.getPlayer()).isSameAs(player);
        assertThat(quiz.getContent()).isEqualTo("이 선수의 2026 시즌 홈런 수는?");
        assertThat(quiz.getAnswer()).isEqualTo(2);
        assertThat(quiz.getScore()).isEqualTo(1.5);
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
    @DisplayName("score는 MVP 이후 도입이라 지정하지 않으면 null로 남는다")
    void score_isNullWhenNotGiven() {
        // given
        Quiz quiz = Quiz.builder()
                .quizType(newQuizType("객관식"))
                .content("점수 미지정 문제")
                .answer(3)
                .build();

        // then
        assertThat(quiz.getScore()).isNull();
    }
}
