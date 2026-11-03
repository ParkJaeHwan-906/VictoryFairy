package com.skhynix.domain.quizroom.repository;

import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizRoomQuizOptionRepository extends JpaRepository<QuizRoomQuizOption, Long> {

    // 방 하나의 보기 전부를 한 번에 — 문항마다 조회하면 문항 수만큼 늘어난다. 호출자가 quiz.id 로 묶는다
    List<QuizRoomQuizOption> findAllByQuiz_Room_IdOrderByQuiz_IdAscNoAsc(Long roomId);

    List<QuizRoomQuizOption> findAllByQuiz_IdOrderByNoAsc(Long quizId);

    boolean existsByQuiz_IdAndNo(Long quizId, int no);
}
