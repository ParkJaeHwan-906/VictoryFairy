package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 주어진 문제들 중 이 계정이 이미 제출한 것의 id 만 추린다 — "푼 문제는 오늘 목록에 노출하지
     * 않는다" 정책의 필터 조회. 엔티티가 아니라 id 프로젝션인 이유: 호출부는 제외 판정에 id 만 쓰고,
     * {@code uk_quiz_users_submit_account_quiz}(user_account_id, quiz_id)가 두 컬럼을 다 담고 있어
     * 테이블 접근 없는 커버링 인덱스 조회로 끝난다.
     */
    @Query("select s.quiz.id from QuizUserSubmit s "
            + "where s.userAccount.id = :userAccountId and s.quiz.id in :quizIds")
    List<Long> findSubmittedQuizIds(@Param("userAccountId") Long userAccountId,
            @Param("quizIds") Collection<Long> quizIds);

    /**
     * 내 제출 이력 한 페이지(최신 제출부터 — 정렬 축은 {@code id}다. {@code createdAt}이 아닌 이유는
     * 같은 초에 여러 건 제출돼도 순서가 흔들리지 않는 유일 축이라서다).
     *
     * <p>to-one 연관 셋({@code quiz}·{@code quiz.quizType}·{@code submitOption})을 전부 fetch join 하는
     * 이유: 이력 DTO 가 문제 지문·유형명·내가 고른 보기 텍스트를 모두 읽는데, {@code open-in-view: false}
     * 라 트랜잭션 밖 LAZY 접근은 예외이고, 안이어도 행마다 3연관 지연 로딩이면 N+1 이다. to-one 만이라
     * {@code Pageable}과 같이 써도 안전하다 — 컬렉션 fetch join + 페이징({@code HHH90003004}) 금지는
     * 여기 해당하지 않는다.
     *
     * <p>{@code countQuery}를 따로 준 이유: fetch join 이 든 JPQL 은 Hibernate 가 count 쿼리를 자동
     * 파생하지 못한다 — 카운트에는 조인이 필요 없으니 단독 카운트로 명시한다.
     */
    @Query(value = "select s from QuizUserSubmit s "
            + "join fetch s.quiz q join fetch q.quizType join fetch s.submitOption "
            + "where s.userAccount.id = :userAccountId order by s.id desc",
            countQuery = "select count(s) from QuizUserSubmit s "
                    + "where s.userAccount.id = :userAccountId")
    Page<QuizUserSubmit> findHistoryByUserAccountId(@Param("userAccountId") Long userAccountId,
            Pageable pageable);

    // 이력 요약(전체 제출 수 / 정답 수) — uk_quiz_users_submit_account_quiz 선행 컬럼이
    // user_account_id 라 커버링 인덱스 카운트다(엔티티 주석의 "내 제출 이력" 예고가 이 자리).
    long countByUserAccount_Id(Long userAccountId);

    long countByUserAccount_IdAndIsAnswerTrue(Long userAccountId);
}
