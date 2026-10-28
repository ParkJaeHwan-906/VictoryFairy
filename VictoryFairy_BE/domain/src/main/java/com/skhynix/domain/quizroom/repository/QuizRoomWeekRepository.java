package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomWeek;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRoomWeekRepository extends JpaRepository<QuizRoomWeek, Long> {

    boolean existsByWeekStart(LocalDate weekStart);

    // 인기 목록 = 가장 최근에 확정된 주. 소급이 바로 앞 주만 하므로 빠진 주가 있으면 더 오래된 주가 나올 수 있다
    Optional<QuizRoomWeek> findTopByOrderByWeekStartDesc();
}
