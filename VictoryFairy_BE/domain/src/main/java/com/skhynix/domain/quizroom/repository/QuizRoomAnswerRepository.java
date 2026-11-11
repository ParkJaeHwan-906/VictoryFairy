package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomAnswer;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRoomAnswerRepository extends JpaRepository<QuizRoomAnswer, Long> {

    boolean existsByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);

    long countByUserAccount_IdAndRoom_Id(Long userAccountId, Long roomId);

    List<QuizRoomAnswer> findAllByUserAccount_IdAndRoom_Id(Long userAccountId, Long roomId);

    // 투표 분포의 정본. 계정 축을 보지 않으므로 소유자가 SET NULL 로 비워진 답도 그대로 센다
    @Query("select new com.skhynix.domain.quizroom.repository.QuizRoomVoteCountView("
            + "a.quiz.id, a.optionNo, count(a)) "
            + "from QuizRoomAnswer a where a.room.id = :roomId group by a.quiz.id, a.optionNo")
    List<QuizRoomVoteCountView> countVotesByRoomId(@Param("roomId") Long roomId);

    @Query("select new com.skhynix.domain.quizroom.repository.QuizRoomVoteCountView("
            + "a.quiz.id, a.optionNo, count(a)) "
            + "from QuizRoomAnswer a where a.quiz.id = :quizId group by a.quiz.id, a.optionNo")
    List<QuizRoomVoteCountView> countVotesByQuizId(@Param("quizId") Long quizId);

    /**
     * 주간 점수의 풀이 축 — 그 방에 <b>처음 답한 시각</b>이 {@code [start, end)} 인 고유 계정 수를 방별로 센다.
     * 그 전 주에 시작한 풀이자의 이번 주 답은 {@code not exists} 가 거른다. 작성자 본인과, SET NULL 로 누구인지 모르게 된 답은 넣지 않는다.
     */
    // 바깥 created_at 범위는 idx_quiz_room_answers_created_at 이 받는다 — 인기 목록(GET /rt/quiz-rooms/popular)이 캐시 없이 요청마다
    // 이번 주 실시간 순위를 내느라 이 쿼리가 요청마다 돈다. 다만 비용의 대부분은 not exists 반조인이다. 반조인은
    // idx_quiz_room_answers_account_room 의 (user_account_id, room_id) ref 로 그 방에서 낸 답(최대 20건)만 보지만, 그 주 답 행마다 돈다.
    // 합성 35만 행 EXPLAIN ANALYZE(MySQL 8.0): 인덱스 없이 118ms → (created_at) 54ms, account_room 에 created_at 을 붙이는 안은 99ms.
    // 대가로 답 제출(문항마다 INSERT)마다 유지비가 붙는다(같은 데이터 일괄 INSERT 4.2s → 6.4s). 제거하지 말 것.
    // 반조인 비용은 그 주 답 수에 비례해 인덱스로는 절반만 준다 — 근본 해결은 (계정·방) 첫 풀이 시각 테이블(후속 과제)
    @Query("select new com.skhynix.domain.quizroom.repository.QuizRoomCountView(a.room.id, count(distinct a.userAccount.id)) "
            + "from QuizRoomAnswer a "
            + "where a.createdAt >= :start and a.createdAt < :end "
            + "and a.userAccount is not null and a.userAccount.id <> a.room.author.id "
            + "and not exists (select 1 from QuizRoomAnswer p where p.room.id = a.room.id "
            + "and p.userAccount.id = a.userAccount.id and p.createdAt < :start) "
            + "group by a.room.id")
    List<QuizRoomCountView> countWeeklyNewSolversByRoom(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
