package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.user.entity.UserAccount;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QuizUserSubmit}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p>중복 제출 차단이 스키마 제약이 아니라 서비스 정책이라(엔티티 javadoc 참고) 이 테스트로는 검증되지
 * 않는다 — 같은 (계정, 문제)로 두 인스턴스를 만드는 것 자체는 막히지 않는다.
 */
class QuizUserSubmitTest {

    private Quiz newQuiz() {
        return Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .content("문제")
                .answer(2)
                .build();
    }

    private UserAccount newUserAccount(String nickname) {
        return UserAccount.builder().nickname(nickname).password("password1!").build();
    }

    @Test
    @DisplayName("Builder로 생성하면 필드가 동일 인스턴스로 배선되고 isAnswer가 그대로 보존된다")
    void builder_wiresFieldsToSameInstance() {
        // given
        Quiz quiz = newQuiz();
        QuizOption submitted = QuizOption.builder().quiz(quiz).contents("2번 보기").option(2).build();
        UserAccount userAccount = newUserAccount("응시자1");

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(userAccount)
                .quiz(quiz)
                .submitOption(submitted)
                .isAnswer(true)
                .build();

        // then
        assertThat(submit.getUserAccount()).isSameAs(userAccount);
        assertThat(submit.getQuiz()).isSameAs(quiz);
        assertThat(submit.getSubmitOption()).isSameAs(submitted);
        assertThat(submit.isAnswer()).isTrue();
    }

    @Test
    @DisplayName("isAnswer는 제출 시점 판정을 그대로 저장한다 — 정답 번호와 다시 대조하지 않는다")
    void isAnswer_keepsSubmittedVerdictWithoutRecomputing() {
        // given: quiz.answer(2)와 어긋나는 판정을 일부러 넣는다(정답 정정 후 과거 기록을 보존하는 상황)
        Quiz quiz = newQuiz();
        QuizOption wrong = QuizOption.builder().quiz(quiz).contents("1번 보기").option(1).build();

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(newUserAccount("응시자2"))
                .quiz(quiz)
                .submitOption(wrong)
                .isAnswer(true)
                .build();

        // then: 엔티티가 재계산하지 않으므로 저장된 판정이 유지된다
        assertThat(submit.getSubmitOption().getOption()).isNotEqualTo(quiz.getAnswer());
        assertThat(submit.isAnswer()).isTrue();
    }

    @Test
    @DisplayName("Builder로 inning을 지정하면 그 값이 그대로 배선된다(QUIZ-INN-1 — 제출한 이닝은 이 "
            + "컬럼 하나에만 보관)")
    void builder_wiresInningField() {
        // given
        Quiz quiz = newQuiz();
        QuizOption option = QuizOption.builder().quiz(quiz).contents("2번 보기").option(2).build();

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(newUserAccount("응시자3"))
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(true)
                .inning(6)
                .build();

        // then
        assertThat(submit.getInning()).isEqualTo(6);
    }

    @Test
    @DisplayName("inning을 지정하지 않으면 null로 남는다(QUIZ-INN-2 — 통계용이라 누락 허용, score "
            + "미지정 시 null인 Quiz와 같은 계열이며 0 등으로 보정되지 않는다)")
    void inning_isNullWhenNotGiven() {
        // given
        Quiz quiz = newQuiz();
        QuizOption option = QuizOption.builder().quiz(quiz).contents("2번 보기").option(2).build();

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(newUserAccount("응시자4"))
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(true)
                .build();

        // then
        assertThat(submit.getInning()).isNull();
    }

    @Test
    @DisplayName("Builder로 game을 지정하면 그 인스턴스가 그대로 배선된다(QUIZ-INN-103 — \"그 행을 받은 "
            + "경기\", quizzes.game_id와는 뜻이 다른 별개 FK)")
    void builder_wiresGameField() {
        // given
        Team home = Team.builder().code("HH").name("한화").build();
        Team away = Team.builder().code("KT").name("KT").build();
        Game game = Game.builder()
                .gameDate(LocalDateTime.of(2026, 8, 7, 18, 30))
                .homeTeam(home)
                .awayTeam(away)
                .gameStatus(GameStatus.builder().name("IN_PROGRESS").build())
                .naverGameId("20260807HHKT02026")
                .currentInning(5)
                .build();
        Quiz quiz = newQuiz();
        QuizOption option = QuizOption.builder().quiz(quiz).contents("2번 보기").option(2).build();

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(newUserAccount("응시자5"))
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(true)
                .game(game)
                .inning(5)
                .build();

        // then
        assertThat(submit.getGame()).isSameAs(game);
    }

    @Test
    @DisplayName("game을 지정하지 않으면 null로 남는다(개정 이전 행·이닝 확보 실패로 세트 자체를 못 준 "
            + "행 등 — nullable인 이유는 옛 행 때문이다)")
    void game_isNullWhenNotGiven() {
        // given
        Quiz quiz = newQuiz();
        QuizOption option = QuizOption.builder().quiz(quiz).contents("2번 보기").option(2).build();

        // when
        QuizUserSubmit submit = QuizUserSubmit.builder()
                .userAccount(newUserAccount("응시자6"))
                .quiz(quiz)
                .submitOption(option)
                .isAnswer(true)
                .build();

        // then
        assertThat(submit.getGame()).isNull();
    }
}
