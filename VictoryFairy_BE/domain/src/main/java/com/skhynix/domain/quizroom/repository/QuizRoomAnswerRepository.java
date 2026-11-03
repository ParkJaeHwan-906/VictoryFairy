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
    // created_at 인덱스는 일부러 두지 않는다 — 주 1회 확정에서만 도는 질의라 바깥은 풀스캔(일관 읽기, 락 없음)이고, 비용의 대부분은
    // not exists 반조인이다. 반조인은 idx_quiz_room_answers_account_room 의 (user_account_id, room_id) ref 로 그 방에서 낸 답(최대 20건)만 본다.
    // 합성 35만 행 EXPLAIN ANALYZE(MySQL 8.0): 현행 118ms, (created_at) 추가 54ms, account_room 에 created_at 을 붙여도 99ms.
    // 인덱스 하나를 더 두면 답 제출(문항마다 INSERT)마다 유지비가 붙는다(같은 데이터 일괄 INSERT 4.2s → 6.4s).
    // 확정이 초 단위로 길어지면 그때 다시 잰다 — 반조인 비용은 그 주 답 수에 비례해 created_at 인덱스로는 줄지 않는다
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
