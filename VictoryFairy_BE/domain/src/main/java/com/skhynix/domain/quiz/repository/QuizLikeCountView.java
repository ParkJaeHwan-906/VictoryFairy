package com.skhynix.domain.quiz.repository;

/**
 * 문제별 좋아요 수 집계 한 줄({@code quizId} → {@code likeCount}).
 *
 * <p>엔티티도 스칼라도 아닌 <b>인터페이스 프로젝션</b>인 이유: 집계 결과는 {@code QuizLike} 행이 아니라
 * (키, 값) 쌍이라 엔티티로 표현되지 않고, 값이 둘이라 {@code long} 하나로도 담기지 않는다. 남은 선택지가
 * {@code Object[]}였는데 그건 호출부가 인덱스와 캐스팅으로 풀어야 해서 select 절에 컬럼이 늘거나 순서가
 * 바뀌어도 컴파일러가 잡아주지 못한다 — 이름 있는 getter 로 받으면 그 변경이 컴파일 시점에 드러난다.
 *
 * <p>⚠ <b>getter 이름과 JPQL의 별칭이 정확히 같아야 바인딩된다.</b> 쿼리의 {@code as quizId} /
 * {@code as likeCount}를 지우거나 다르게 쓰면 매핑이 조용히 깨져 런타임에야 드러난다.
 *
 * <p>domain 최초의 프로젝션 사용처다 — 다른 리포지토리는 엔티티나 스칼라만 반환한다.
 */
public interface QuizLikeCountView {

    Long getQuizId();

    long getLikeCount();
}
