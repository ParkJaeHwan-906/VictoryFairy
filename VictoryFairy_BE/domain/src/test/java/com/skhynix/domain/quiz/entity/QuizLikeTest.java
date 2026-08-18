package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.domain.user.entity.UserAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QuizLike}의 Builder 필드 배선과 전이 메서드({@code toggle}/{@code isLiked})만 검증하는
 * 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p><b>DB 전략 관련 결정</b>: {@code domain} 모듈에 {@code @DataJpaTest} 실행에 필요한
 * H2/Testcontainers/구동 중인 MySQL이 전혀 없어({@link com.skhynix.domain.stadium.entity.StadiumTest}
 * javadoc 참고) 실제 저장·조회 라운드트립은 다루지 않는다. {@code user_account_id}/{@code quiz_id} FK
 * 제약, {@code uk_quizzes_like_account_quiz} UNIQUE 제약은 이 테스트로 검증되지 않는다.
 */
class QuizLikeTest {

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
    @DisplayName("Builder(userAccount, quiz)로 생성하면 필드가 동일 인스턴스로 배선되고 liked는 true(켜짐)로 초기화된다")
    void builder_wiresFieldsToSameInstanceAndInitializesLikedAsTrue() {
        // given
        Quiz quiz = newQuiz();
        UserAccount userAccount = newUserAccount("팬1");

        // when
        QuizLike quizLike = QuizLike.builder().userAccount(userAccount).quiz(quiz).build();

        // then
        assertThat(quizLike.getUserAccount()).isSameAs(userAccount);
        assertThat(quizLike.getQuiz()).isSameAs(quiz);
        assertThat(quizLike.isLiked()).isTrue();
    }

    @Test
    @DisplayName("빌더는 liked 파라미터를 받지 않는다 — 행이 생기는 계기는 좋아요를 눌렀다는 것 하나뿐이라 "
            + "꺼진 채로 태어나는 행을 만들 수단이 존재하지 않는다")
    void builder_hasNoLikedParameter() {
        // 이 테스트는 컴파일 시점 자체가 단언이다: QuizLike.builder()에 .liked(...)를 호출하는 코드는
        // 그 자체로 컴파일되지 않는다. 런타임 단언으로는 "초기값이 항상 true"라는 사실만 재확인한다.
        QuizLike quizLike = QuizLike.builder()
                .userAccount(newUserAccount("팬2"))
                .quiz(newQuiz())
                .build();

        assertThat(quizLike.isLiked()).isTrue();
    }

    @Test
    @DisplayName("toggle()을 호출하면 liked가 true에서 false로 뒤집힌다(좋아요 취소)")
    void toggle_flipsLikedFromTrueToFalse() {
        // given
        QuizLike quizLike = QuizLike.builder()
                .userAccount(newUserAccount("팬3"))
                .quiz(newQuiz())
                .build();
        assertThat(quizLike.isLiked()).isTrue();

        // when
        quizLike.toggle();

        // then
        assertThat(quizLike.isLiked()).isFalse();
    }

    @Test
    @DisplayName("좋아요 -> 취소 -> 좋아요 왕복 후에도 liked는 true로 정확히 되돌아온다")
    void toggle_roundTrip_endsUpLikedAgain() {
        // given
        QuizLike quizLike = QuizLike.builder()
                .userAccount(newUserAccount("팬4"))
                .quiz(newQuiz())
                .build();

        // when
        quizLike.toggle(); // true -> false
        quizLike.toggle(); // false -> true

        // then
        assertThat(quizLike.isLiked()).isTrue();
    }
}
