package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.Quiz;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 아직 커스텀 조회 메서드가 없다 — 출제·조회 서비스가 생길 때 필요한 것만 추가한다.
 *
 * <p>추가 시 주의: 목록 응답이 {@code quizType}·{@code team}·{@code player}를 읽는다면 전부 LAZY 라
 * {@code @EntityGraph}가 없으면 N+1 이 나고, prod({@code open-in-view: false})에서는
 * {@code LazyInitializationException}이 된다({@code GameRepository}의 날짜별 목록 조회가 선례).
 * 반대로 내부 PK 만 꺼내 쓰는 조회에는 붙이지 말 것 — 안 쓰는 조인이 매 요청 붙는다.
 */
public interface QuizRepository extends JpaRepository<Quiz, Long> {
}
