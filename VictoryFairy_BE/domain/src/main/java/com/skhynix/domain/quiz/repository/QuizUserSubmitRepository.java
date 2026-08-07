package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizUserSubmitRepository extends JpaRepository<QuizUserSubmit, Long> {

    /**
     * 해당 계정이 그 문제에 이미 제출했는지 본다. 중복 제출 차단이 스키마 제약이 아니라 서비스 정책이라
     * (엔티티 javadoc 참고) 쓰기 경로가 이 조회로 직접 막아야 한다.
     *
     * <p>{@code Optional}인 것은 "제출은 최대 1건"이라는 <b>정책</b>을 전제한 것이다 — 재제출을 새 행으로
     * 쌓기로 결정하면 이 반환형이 먼저 깨지므로, 그때 {@code List}로 바꾸고 호출부를 함께 손봐야 한다.
     */
    Optional<QuizUserSubmit> findByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);

    boolean existsByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);
}
