package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizOption;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizOptionRepository extends JpaRepository<QuizOption, Long> {

    /**
     * 한 문제의 보기를 화면 표기 순서로 가져온다. 정렬 축이 PK 나 생성 순서가 아니라
     * {@code option}(보기 번호)인 것이 핵심이다 — 보기를 다시 만들어도 표기 순서가 흔들리지 않는다.
     *
     * <p>연관 경로를 {@code Quiz_Id}로 끊어 {@code quiz.id}(FK 값)로만 해석되게 했다
     * ({@code PlayerRepository.findAllByTeam_Id...}와 같은 이유).
     */
    List<QuizOption> findAllByQuiz_IdOrderByOptionAsc(Long quizId);
}
