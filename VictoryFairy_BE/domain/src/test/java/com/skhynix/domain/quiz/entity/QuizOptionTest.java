package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QuizOption}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p>컬럼명 {@code option}이 MySQL 예약어라 백틱이 필요하다는 사실은 <b>DDL 생성 시점</b>에만 드러나므로
 * 이 테스트로는 잡히지 않는다({@link QuizTest} javadoc 의 DB 전략 참고).
 */
class QuizOptionTest {

    private Quiz newQuiz() {
        return Quiz.builder()
                .quizType(QuizType.builder().name("객관식").build())
                .content("문제")
                .answer(1)
                .build();
    }

    @Test
    @DisplayName("Builder로 생성하면 필드가 동일 인스턴스로 배선된다")
    void builder_wiresFieldsToSameInstance() {
        // given
        Quiz quiz = newQuiz();

        // when
        QuizOption option = QuizOption.builder().quiz(quiz).contents("1번 보기").option(1).build();

        // then
        assertThat(option.getQuiz()).isSameAs(quiz);
        assertThat(option.getContents()).isEqualTo("1번 보기");
        assertThat(option.getOption()).isEqualTo(1);
    }

    @Test
    @DisplayName("O/X 유형은 보기 번호를 0(X)/1(O)로 표기한다")
    void option_usesZeroAndOneForOxType() {
        // given
        Quiz quiz = newQuiz();

        // when
        QuizOption x = QuizOption.builder().quiz(quiz).contents("X").option(0).build();
        QuizOption o = QuizOption.builder().quiz(quiz).contents("O").option(1).build();

        // then
        assertThat(x.getOption()).isZero();
        assertThat(o.getOption()).isEqualTo(1);
    }
}
