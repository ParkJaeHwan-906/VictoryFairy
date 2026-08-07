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
     * ⚠ 스키마가 그 전제를 강제하지 않으므로, 어떤 경로로든 2행이 생기면 이 조회가
     * {@code IncorrectResultSizeDataAccessException}으로 죽어 그 사용자는 해당 문제에 영영 접근하지 못한다.
     * <b>존재 여부만 필요한 자리에서는 아래 {@code existsBy}를 쓸 것</b> — 엔티티를 안 만들고, 아래 주석대로
     * 커버링 인덱스로 끝나며, 2행이어도 죽지 않는다.
     *
     * <p>둘 다 {@code QuizUserSubmit}의 {@code idx_quiz_users_submit_account_quiz}에 의존한다(근거는 그쪽
     * 주석). 인덱스가 없으면 FK 자동 인덱스 둘로 index_merge 가 돌고 그 비용이 문제 인기도에 비례해 자란다.
     */
    Optional<QuizUserSubmit> findByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);

    boolean existsByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);
}
