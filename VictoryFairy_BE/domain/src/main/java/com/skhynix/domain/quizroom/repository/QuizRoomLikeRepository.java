package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomLike;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizRoomLikeRepository extends JpaRepository<QuizRoomLike, Long> {

    boolean existsByUserAccount_IdAndRoom_Id(Long userAccountId, Long roomId);

    @Modifying
    @Query("delete from QuizRoomLike l where l.userAccount.id = :userAccountId and l.room.id = :roomId")
    int deleteByUserAccountIdAndRoomId(@Param("userAccountId") Long userAccountId,
            @Param("roomId") Long roomId);

    /**
     * 주간 점수의 추천 축 — {@code [start, end)} 에 생겨 지금까지 남은 추천 수를 방별로 센다. 취소는 행 삭제라 자연히 빠진다.
     * 작성자 본인 추천은 넣지 않는다. 계정이 SET NULL 로 비워진 추천은 작성자 것인지 가릴 수 없어 남이 한 추천으로 센다.
     */
    // created_at 범위는 idx_quiz_room_likes_created_at 이 받는다 — 인기 목록(GET /rt/quiz-rooms/popular)이 캐시 없이 요청마다
    // 이번 주 실시간 순위를 내느라 이 쿼리가 요청마다 돌고, 인덱스가 없으면 매번 테이블 풀스캔이다.
    // 합성 6만 행 EXPLAIN ANALYZE(MySQL 8.0): 풀스캔 13ms → 인덱스 2.5ms. 대가로 추천·해제(INSERT·DELETE)마다 인덱스 유지비가 붙는다.
    // 제거하지 말 것 — 지우면 인기 목록 요청마다 풀스캔으로 돌아간다
    @Query("select new com.skhynix.domain.quizroom.repository.QuizRoomCountView(l.room.id, count(l)) "
            + "from QuizRoomLike l "
            + "where l.createdAt >= :start and l.createdAt < :end "
            + "and (l.userAccount is null or l.userAccount.id <> l.room.author.id) "
            + "group by l.room.id")
    List<QuizRoomCountView> countWeeklyLikesByRoom(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
