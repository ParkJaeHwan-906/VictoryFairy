package com.skhynix.domain.quiz.repository;

import java.time.LocalDateTime;

/**
 * {@code (계정, 문제)} 행의 <b>상태 판정에 필요한 최소 컬럼</b>만 뽑는 프로젝션.
 * {@code GET /today}가 이 셋으로 세 가지를 한꺼번에 결정한다:
 * <ul>
 *   <li>{@code submitOptionId != null} → 답한 문제라 <b>목록에서 제외</b></li>
 *   <li>{@code submitOptionId == null} 인데 {@code createdAt} + 시한이 지남 → 시한 초과라 <b>제외</b></li>
 *   <li>둘 다 아니면 진행 중이라 <b>다시 서빙</b>(행이 이미 있으므로 INSERT 대상에서만 빠진다)</li>
 * </ul>
 *
 * <p>엔티티가 아니라 프로젝션인 이유는 {@code findSubmittedQuizIds}(구)와 같다 — 호출부가 id·null 여부·
 * 시각만 읽는데 엔티티를 만들면 영속성 컨텍스트에 세트 전체가 올라간다. 다만 <b>커버링 인덱스는 아니다</b>:
 * {@code uk_quiz_users_submit_account_quiz}에 {@code submit_option_id}·{@code created_at}이 없어 행 접근이
 * 생긴다(선행 컬럼 {@code user_account_id}로 진입 범위는 그대로 좁다). 그 대가로 "받자마자 목록에서
 * 사라지는" 사고를 막으므로 감수한다.
 *
 * <p>인터페이스 프로젝션은 {@code QuizLikeCountView} 선례를 따른다 — {@code Object[]}는 호출부가 인덱스와
 * 캐스팅으로 풀어야 해서 select 절이 바뀌어도 컴파일러가 못 잡는다.
 */
public interface QuizUserSubmitStateView {

    Long getQuizId();

    /** 고른 보기의 FK 값. {@code null} 이면 <b>아직 답하지 않은 행</b>이다. */
    Long getSubmitOptionId();

    /** 행이 생긴 시각 = 그 문제를 받은 시각. 시한(+8분)의 유일한 기준점이다. */
    LocalDateTime getCreatedAt();
}
