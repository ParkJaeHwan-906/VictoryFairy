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
    // created_at 인덱스는 일부러 두지 않는다 — 주 1회 확정에서만 도는 풀스캔(일관 읽기, 락 없음)이다. 합성 6만 행 EXPLAIN ANALYZE
    // (MySQL 8.0)에서 13ms, 인덱스를 둬도 2.5ms 라 아끼는 시간이 추천·해제(INSERT·DELETE)마다 붙는 유지비보다 작다
    @Query("select new com.skhynix.domain.quizroom.repository.QuizRoomCountView(l.room.id, count(l)) "
            + "from QuizRoomLike l "
            + "where l.createdAt >= :start and l.createdAt < :end "
            + "and (l.userAccount is null or l.userAccount.id <> l.room.author.id) "
            + "group by l.room.id")
    List<QuizRoomCountView> countWeeklyLikesByRoom(@Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
