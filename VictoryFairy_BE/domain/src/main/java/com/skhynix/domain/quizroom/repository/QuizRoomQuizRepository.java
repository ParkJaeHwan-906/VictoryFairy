package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomQuiz;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRoomQuizRepository extends JpaRepository<QuizRoomQuiz, Long> {

    List<QuizRoomQuiz> findAllByRoom_IdOrderByIdAsc(Long roomId);

    // 방 조건을 함께 건다 — 다른 방(또는 다른 테이블)의 문항 id 로는 찾을 수 없어야 한다
    Optional<QuizRoomQuiz> findByIdAndRoom_Id(Long id, Long roomId);
}
