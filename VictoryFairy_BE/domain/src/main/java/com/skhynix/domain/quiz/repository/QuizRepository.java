package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.Quiz;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    /**
     * 적재 멱등 검사 — S3 후보의 {@code quizId}가 이미 들어왔는지. 로더가 후보마다 먼저 부른다.
     * 이 검사와 INSERT 사이의 race(파드 동시 실행)는 {@code uk_quizzes_external_id} UNIQUE 가
     * 원자적으로 막으므로, 로더는 제약 위반을 "이미 적재됨"으로 해석해 조용히 건너뛰면 된다.
     */
    boolean existsByExternalId(String externalId);

    /**
     * 출제일 기준 목록 — "오늘의 퀴즈" 조회. {@code idx_quizzes_quiz_date} 인덱스를 탄다.
     *
     * <p>{@code @EntityGraph}가 {@code quizType}만 싣는 이유: 응답 DTO 가 읽는 연관이 유형명뿐이라서다
     * ({@code GameRepository}의 목록 조회와 같은 원칙 — DTO 가 읽는 연관과 1:1 유지). team/player/game
     * 을 응답에 싣게 되면 여기에도 함께 추가할 것 — 안 그러면 N+1 이 나고, prod({@code open-in-view:
     * false})에서는 {@code LazyInitializationException}이 된다. 보기는 이 그래프로 못 싣는다
     * ({@code Quiz}에 {@code @OneToMany options}가 없음) — {@code QuizOptionRepository}의 IN 조회로
     * 묶는 2쿼리 방식이 정석이다.
     */
    @EntityGraph(attributePaths = {"quizType"})
    List<Quiz> findAllByQuizDateOrderByIdAsc(LocalDate quizDate);
}
