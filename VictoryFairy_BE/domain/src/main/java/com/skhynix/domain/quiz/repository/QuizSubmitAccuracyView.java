package com.skhynix.domain.quiz.repository;

/**
 * 한 계정의 누적 정답률 재료 한 줄({@code totalCount} / {@code correctCount}).
 *
 * <p>{@link QuizLikeCountView}와 같은 <b>인터페이스 프로젝션</b>이다 — 값이 둘이라 스칼라 하나로 담기지
 * 않고, 집계 결과라 엔티티도 아니다. 나눗셈을 SQL 에서 끝내지 않고 두 수를 그대로 올리는 이유는 반올림
 * 규칙(소수 셋째 자리·HALF_UP)이 응답 계약이라 DB 방언의 나눗셈 결과에 맡길 값이 아니기 때문이다.
 *
 * <p>⚠ <b>getter 이름과 JPQL 별칭이 정확히 같아야 바인딩된다</b>(별칭을 지우면 런타임에야 드러난다).
 *
 * <p>⚠ {@code correctCount}만 {@code Long}인 것은 오타가 아니다 — 행이 한 건도 없는 계정에서
 * {@code count}는 0을 주지만 {@code sum}은 <b>NULL</b>을 준다. 원시 타입으로 받으면 그 계정에서 NPE 다.
 * 호출부는 {@code totalCount == 0}을 먼저 걸러 이 값을 읽지 않는다.
 */
public interface QuizSubmitAccuracyView {

    long getTotalCount();

    Long getCorrectCount();
}
