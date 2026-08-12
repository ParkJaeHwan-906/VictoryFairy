package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.Quiz;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * <p>{@code @EntityGraph}는 <b>응답·판정이 실제로 읽는 연관과 1:1</b>로 유지한다
     * ({@code GameRepository}의 목록 조회와 같은 원칙). {@code quizType}은 응답 DTO 의 유형명,
     * {@code team}/{@code opponentTeam}/{@code player}는 선호(preferred) 매칭 판정이 id 비교로
     * 읽는다 — 빼면 문제마다 프록시 초기화 쿼리가 붙어 N+1 이고, prod({@code open-in-view: false})
     * 에서는 {@code LazyInitializationException}이 된다. 보기는 이 그래프로 못 싣는다
     * ({@code Quiz}에 {@code @OneToMany options}가 없음) — {@code QuizOptionRepository}의 IN 조회로
     * 묶는 2쿼리 방식이 정석이다.
     *
     * <p>{@code game} 축은 제출 티켓에 실을 이닝({@code games.current_inning})을 읽으려고 더했다 —
     * 응답 필드는 아니지만 <b>같은 원칙(읽는 연관과 1:1)</b>이 적용된다. 빼면 문제마다 {@code games}
     * 단건 조회가 붙어 N+1 이 된다.
     */
    @EntityGraph(attributePaths = {"quizType", "team", "opponentTeam", "player", "game"})
    List<Quiz> findAllByQuizDateOrderByIdAsc(LocalDate quizDate);

    /** 그날 세트의 현재 크기 — 편성 잡이 부족분 계산에 쓴다. */
    long countByQuizDate(LocalDate quizDate);

    /**
     * 미편성 풀({@code quiz_date IS NULL})에서 {@code limit}건을 그날 세트로 편성(날짜 스탬프)한다.
     * 반환값은 실제로 스탬프된 행 수(풀이 부족하면 limit 미만).
     *
     * <p><b>{@code ORDER BY id ASC} — 오래된 것부터 결정적으로 채운다.</b> 어떤 행이 뽑히는지가
     * 실행 시점·파드에 따라 갈리면 같은 날 두 번 실행이 서로 다른 문제를 편성할 수 있는데, 순서를
     * 고정하면 항상 같은 행을 겨냥하므로 선택이 갈리지 않는다. UPDATE ... ORDER BY LIMIT 은 JPQL 에
     * 없어 네이티브다.
     *
     * <p>멀티 파드가 동시에 실행하면 카운트-업데이트가 비원자라 최대 2배까지 과편성될 수 있다.
     * <b>허용</b> — 그날 문제 수가 늘어날 뿐 세트는 여전히 전원 동일하고, 락(ShedLock 등)을 들이는
     * 비용이 이 무해한 결과보다 크다.
     */
    @Modifying
    @Query(value = "UPDATE quizzes SET quiz_date = :quizDate "
            + "WHERE quiz_date IS NULL ORDER BY id ASC LIMIT :limit", nativeQuery = true)
    int publishFromPool(@Param("quizDate") LocalDate quizDate, @Param("limit") int limit);
}
