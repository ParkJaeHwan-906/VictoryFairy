package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizUserSubmitRepository
        extends JpaRepository<QuizUserSubmit, Long>, QuizUserSubmitRepositoryCustom {

    /**
     * 그 {@code (계정, 문제)} 행을 가져온다 — <b>"제출했는가"가 아니라 "받았는가"</b>다. 행은 출제 시점에
     * 생기므로(엔티티 javadoc 의 세 상태) 존재만으로는 제출을 뜻하지 않는다. 답 유무는
     * {@code getSubmitOption() == null} 로 갈린다({@code submit_option_id} 가 행에 있어 이 판정에는
     * 추가 조회가 없다).
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

    // ⚠ 존재 = "받았다"이지 "답했다"가 아니다. 좋아요 자격 판정(QuizLikeToggler)이 이 메서드를 쓰므로
    //   좋아요는 이제 '푼 문제'가 아니라 '오늘의 퀴즈로 받은 문제'에 열린다(제품 결정, 코드 변경 없음).
    boolean existsByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);

    /**
     * 주어진 문제들에 대한 이 계정의 행 상태 — {@code GET /today}의 <b>제외 필터이자 INSERT 차집합의
     * 재료</b>다(조회를 둘로 나누지 않는다).
     *
     * <p>⚠ <b>"행이 있으면 제외"로 되돌리지 말 것.</b> 행은 이제 출제 시점에 생기므로, 그렇게 하면
     * 문제를 받는 순간 목록에서 사라져 <b>새로고침 한 번에 그날 세트를 통째로 못 풀게 된다.</b>
     * 제외 기준은 {@link QuizUserSubmitStateView} 의 세 갈래다.
     *
     * <p>⚠ 보기 연관은 <b>명시적 {@code left join}</b> 이어야 한다. 암묵 경로({@code s.submitOption.id})는
     * Hibernate 가 FK 컬럼으로 최적화해 주기를 기대하는 것이고, 그 기대가 어긋나 inner join 이 되면
     * <b>미답 행이 결과에서 통째로 빠진다</b> — 그러면 시한 초과 행을 영영 못 걸러 그 문제가 계속
     * 목록에 남고 제출은 계속 403 이 된다(조용히 틀리는 종류의 회귀라 조인 하나를 감수한다).
     */
    @Query("select s.quiz.id as quizId, so.id as submitOptionId, "
            + "s.createdAt as createdAt from QuizUserSubmit s left join s.submitOption so "
            + "where s.userAccount.id = :userAccountId and s.quiz.id in :quizIds")
    List<QuizUserSubmitStateView> findSubmitStates(@Param("userAccountId") Long userAccountId,
            @Param("quizIds") Collection<Long> quizIds);

    /**
     * 미답 행에 답과 채점 결과를 채운다 — <b>제출은 INSERT 가 아니라 이 조건부 UPDATE 다.</b>
     * 반환값 0 은 실패가 아니라 판정이다: <b>이미 답이 채워진 행</b>(=중복 제출 → 409)이라는 뜻이며,
     * 동시 제출 2건 중 진 쪽도 여기서 0 을 받는다(선검사 {@code existsBy} + UNIQUE 위반 변환의 이중
     * 구조를 이 한 방이 대체한다).
     *
     * <p>⚠ <b>{@code createdAt} 조건을 빼지 말 것.</b> {@code submitOption IS NULL} 만 걸면 시한을 한참
     * 넘긴 행도 그대로 갱신돼 <b>8시간 뒤 제출이 정답 처리된다.</b> 호출부는 시한 초과를 먼저 403 으로
     * 거르지만, 검사와 이 UPDATE 사이에 시한이 지나는 경우까지 닫는 것은 이 조건뿐이다.
     *
     * <p>{@code updatedAt} 을 직접 대입하는 이유: 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않아
     * {@code @UpdateTimestamp} 가 동작하지 않는다(값을 안 넣으면 출제 시각이 그대로 남는다).
     *
     * @param earliestValidCreatedAt 아직 시한이 남은 행의 최소 {@code created_at}(= 지금 - 8분).
     *     ⚠ {@code created_at} 과 <b>같은 클록 기준</b>(JVM 기본 존)이어야 한다 — 커스텀 프래그먼트
     *     javadoc 의 경고와 같은 함정이다
     */
    @Modifying
    @Query("update QuizUserSubmit s set s.submitOption = :option, s.isAnswer = :correct, "
            + "s.updatedAt = :now where s.userAccount.id = :userAccountId and s.quiz.id = :quizId "
            + "and s.submitOption is null and s.createdAt >= :earliestValidCreatedAt")
    int fillAnswer(@Param("userAccountId") Long userAccountId, @Param("quizId") Long quizId,
            @Param("option") QuizOption option, @Param("correct") boolean correct,
            @Param("earliestValidCreatedAt") LocalDateTime earliestValidCreatedAt,
            @Param("now") LocalDateTime now);

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
     * <p>⚠ <b>{@code submitOption} 만 {@code left join fetch} 다.</b> inner join 이면 답 없는 행
     * ({@code submit_option_id IS NULL})이 목록에서 통째로 빠지는데, 아래 {@code countQuery}는 그 행까지
     * 세므로 <b>"총 20건인데 항목 9건"</b>으로 어긋난다. 감추는 선택지가 없는 이유가 이 비대칭이다 —
     * 미답 행은 요약 통계의 분모에 들어가고(내지 않으면 틀린 것), 그러면 목록에도 있어야 한다.
     * {@code countQuery}는 조인이 없으므로 <b>여기만 고치면 된다</b>(양쪽을 같이 고치려다 count 에
     * 조인을 더하면 그게 회귀다).
     *
     * <p>{@code countQuery}를 따로 준 이유: fetch join 이 든 JPQL 은 Hibernate 가 count 쿼리를 자동
     * 파생하지 못한다 — 카운트에는 조인이 필요 없으니 단독 카운트로 명시한다.
     */
    @Query(value = "select s from QuizUserSubmit s "
            + "join fetch s.quiz q join fetch q.quizType left join fetch s.submitOption "
            + "where s.userAccount.id = :userAccountId order by s.id desc",
            countQuery = "select count(s) from QuizUserSubmit s "
                    + "where s.userAccount.id = :userAccountId")
    Page<QuizUserSubmit> findHistoryByUserAccountId(@Param("userAccountId") Long userAccountId,
            Pageable pageable);

    // 이력 요약(전체 받은 수 / 정답 수) — uk_quiz_users_submit_account_quiz 선행 컬럼이
    // user_account_id 라 커버링 인덱스 카운트다(엔티티 주석의 "내 제출 이력" 예고가 이 자리).
    // ⚠ 분모(total)에 답 없는 행도 들어간다 — 제출하지 않으면 틀린 것이라는 제품 결정이라, is_answer
    //   가 false 인 미답 행이 오답과 똑같이 집계되는 것이 의도다. "미답을 빼자"는 최적화는 결정에
    //   반한다(그 대가로 /today 직후 정확도가 0% 로 떨어졌다가 풀수록 올라간다 — 수용된 결과).
    long countByUserAccount_Id(Long userAccountId);

    long countByUserAccount_IdAndIsAnswerTrue(Long userAccountId);
}
