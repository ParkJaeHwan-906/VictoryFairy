package com.skhynix.domain.quiz.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link QuizType}의 Builder 필드 배선만 검증하는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 * {@code PositionTest}와 같은 성격의 코드 테이블 엔티티 테스트다.
 */
class QuizTypeTest {

    @Test
    @DisplayName("Builder(name)로 생성하면 name이 배선된다")
    void builder_wiresName() {
        // when
        QuizType quizType = QuizType.builder().name("객관식").build();

        // then
        assertThat(quizType.getName()).isEqualTo("객관식");
    }

    @Test
    @DisplayName("name은 닫힌 집합이 아니라 행으로 존재한다 — 새 유형도 그대로 만들어진다")
    void name_isNotAClosedSet() {
        // when
        QuizType quizType = QuizType.builder().name("주관식").build();

        // then
        assertThat(quizType.getName()).isEqualTo("주관식");
    }
}
