package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.Quiz;
import java.time.LocalDate;
import java.util.Collection;
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
     *
     * <p><b>{@code AND (settlement_metric IS NULL OR answer IS NOT NULL)}</b> — 미정산 PREDICTION
     * (정답을 아직 모르는 문제)이 섞여 들어가는 것을 막는다. {@code quiz_date IS NULL}만으로는
     * 걸러지지 않는다 — PREDICTION 은 게임 귀속이라 적재 시점에 이미 {@code quiz_date}가 찍히는 게
     * 보통이지만, game FK 해석이 실패하거나 사람이 직접 풀 대기로 넣는 경로가 생기면 풀에 들어올 수
     * 있다. 들어가면 "오늘의 퀴즈"에 답 없는 문제가 나가 제출 시점에 {@code NullPointerException}
     * 류로 터진다.
     */
    @Modifying
    @Query(value = "UPDATE quizzes SET quiz_date = :quizDate "
            + "WHERE quiz_date IS NULL AND (settlement_metric IS NULL OR answer IS NOT NULL) "
            + "ORDER BY id ASC LIMIT :limit", nativeQuery = true)
    int publishFromPool(@Param("quizDate") LocalDate quizDate, @Param("limit") int limit);

    /**
     * 경기 기반 선택(계약 {@code docs/requirements/quiz/game-scoped-selection.md}, QUIZ-GSS-14)의
     * <b>classifier(소속 판정)</b>를 대신 거는 조회 4종. classifier 는 선수 문제(player ≠ null)는
     * {@code player.team}, 그 외(구단·맞대결·경기 전용 문제)는 {@code quiz.team} 단일 기준이다 —
     * {@code opponentTeam}은 classifier 로 쓰지 않는다(맞대결·경기 전용 문제도 {@code quiz.team} 쪽
     * 팀으로만 분류된다). 일반 퀴즈({@code team}·{@code player}·{@code game} 전부 null)는 이 조건에
     * 자연히 걸리지 않는다(아래 "바닥 단계" 전용 메서드가 따로 담당).
     */
    /** C절 1·3단계 — {@code quiz_date = date}(보통 오늘) && classifier = teamId. */
    @EntityGraph(attributePaths = {"quizType", "team", "opponentTeam", "player", "game"})
    @Query("SELECT q FROM Quiz q WHERE q.quizDate = :date AND ("
            + "(q.player IS NOT NULL AND q.player.team.id = :teamId) "
            + "OR (q.player IS NULL AND q.team.id = :teamId))")
    List<Quiz> findByQuizDateAndClassifierTeam(@Param("date") LocalDate date,
            @Param("teamId") Long teamId);

    /**
     * C절 2·4단계 — "다른 날짜"({@code quiz_date}가 NULL이 아니고 {@code today}도 아님) && classifier =
     * teamId. 미편성 대기 풀({@code quiz_date IS NULL})은 이 조건에서 자연히 빠진다.
     */
    @EntityGraph(attributePaths = {"quizType", "team", "opponentTeam", "player", "game"})
    @Query("SELECT q FROM Quiz q WHERE q.quizDate IS NOT NULL AND q.quizDate <> :today AND ("
            + "(q.player IS NOT NULL AND q.player.team.id = :teamId) "
            + "OR (q.player IS NULL AND q.team.id = :teamId))")
    List<Quiz> findByOtherQuizDateAndClassifierTeam(@Param("today") LocalDate today,
            @Param("teamId") Long teamId);

    /**
     * D절(무관한 경기) — {@code quiz_date = date}(오늘) && classifier ∈ teamIds(기준 경기의 홈·어웨이
     * 양팀). 날짜 캐스케이드도 응원 여부 가중치도 없는 단일 단계다(계약 QUIZ-GSS-8-2).
     */
    @EntityGraph(attributePaths = {"quizType", "team", "opponentTeam", "player", "game"})
    @Query("SELECT q FROM Quiz q WHERE q.quizDate = :date AND ("
            + "(q.player IS NOT NULL AND q.player.team.id IN :teamIds) "
            + "OR (q.player IS NULL AND q.team.id IN :teamIds))")
    List<Quiz> findByQuizDateAndClassifierTeamIn(@Param("date") LocalDate date,
            @Param("teamIds") Collection<Long> teamIds);

    /**
     * C절 5단계(바닥) — {@code quiz_date IS NOT NULL}(오늘 + "다른 날짜", 미편성 풀 제외)인 일반 퀴즈
     * ({@code isGeneralQuiz()} — team·player·game 전부 null) 전체. 1~4단계와 같은 날짜 범위를 그대로
     * 재사용하며 오늘/다른 날짜로 다시 쪼개지 않는다(계약 AC-GSS-20-1).
     */
    @EntityGraph(attributePaths = "quizType")
    @Query("SELECT q FROM Quiz q WHERE q.quizDate IS NOT NULL "
            + "AND q.team IS NULL AND q.player IS NULL AND q.game IS NULL")
    List<Quiz> findGeneralQuizzesWithQuizDate();

    /**
     * D절 바닥(계약 AC-GSS-20-3) — {@code quiz_date = date}(오늘)로 한정된 일반 퀴즈. QUIZ-GSS-8의
     * "오늘 세트 한정"을 그대로 물려받아 다른 날짜 일반 퀴즈는 대상이 아니다.
     */
    @EntityGraph(attributePaths = "quizType")
    @Query("SELECT q FROM Quiz q WHERE q.quizDate = :date "
            + "AND q.team IS NULL AND q.player IS NULL AND q.game IS NULL")
    List<Quiz> findGeneralQuizzesByQuizDate(@Param("date") LocalDate date);

    /**
     * PREDICTION 정산 대상 조회({@code QuizSettlementService#settleInningEvent}) — 특정 경기의
     * 특정 (이닝, 초/말)을 보도록 설정됐고 아직 답이 없는 문제 전부. {@code game}·{@code player}를
     * 함께 실어 N+1 없이 {@code player.kboPlayerId} 비교까지 끝낸다(둘 다 정산이 실제로 읽는 연관).
     */
    @EntityGraph(attributePaths = {"game", "player"})
    List<Quiz> findByGame_IdAndSettlementInningAndSettlementHalfAndAnswerIsNull(
            Long gameId, Integer settlementInning, Integer settlementHalf);

    /**
     * {@code sweepUnresolved} 전용 — 아직 정산되지 않은 PREDICTION 전부
     * ({@code settlementMetric IS NOT NULL AND answer IS NULL}). {@code game.gameStatus}까지 함께
     * 실어 "그 경기가 끝났는가" 판정에 건당 추가 쿼리가 붙지 않게 한다.
     */
    @EntityGraph(attributePaths = {"game", "game.gameStatus"})
    List<Quiz> findBySettlementMetricIsNotNullAndAnswerIsNull();
}
