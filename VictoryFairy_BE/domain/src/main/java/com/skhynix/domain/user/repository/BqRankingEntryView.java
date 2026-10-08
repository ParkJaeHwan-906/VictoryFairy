package com.skhynix.domain.user.repository;

/**
 * 순위표 한 줄의 재료(닉네임·이미지 EP·점수). 순위 숫자는 여기 없다 — 목록의 순위는 정렬된 접두 목록에서
 * 호출부가 매기고, 본인 순위는 별도 count 로 얻는다({@link UserBqRepository#countHigherInTeam}).
 *
 * <p>{@link com.skhynix.domain.quiz.repository.QuizSubmitAccuracyView} 와 같은 인터페이스 프로젝션이다.
 * ⚠ <b>getter 이름과 JPQL 별칭이 정확히 같아야 바인딩된다.</b>
 *
 * <p>{@code bqScore} 가 원시 타입인 것은 쿼리가 {@code coalesce(..., 0)} 로 NULL 을 이미 접었기 때문이다 —
 * {@code users_bq} 행이 없는 계정도 0 점으로 올라온다.
 */
public interface BqRankingEntryView {

    String getNickname();

    String getProfileImgUrl();

    long getBqScore();
}
