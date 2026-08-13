package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizLike;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizLikeRepository extends JpaRepository<QuizLike, Long> {

    /**
     * 토글 대상 행을 찾는다 — 있으면 뒤집고, 없으면 새로 만든다.
     *
     * <p>{@code Optional} 단건인 근거는 정책이 아니라 스키마다({@code uk_quizzes_like_account_quiz}).
     * 그래서 여기서는 {@code IncorrectResultSizeDataAccessException}을 걱정할 필요가 없다 —
     * {@code QuizUserSubmitRepository.findByUserAccount_IdAndQuiz_Id}가 안고 있는 그 위험은 제출 쪽
     * UNIQUE 승격 전 이야기였고, 이 테이블은 처음부터 제약을 갖고 태어난다.
     *
     * <p>연관 경로를 {@code UserAccount_Id}/{@code Quiz_Id}로 끊어 FK 컬럼만 조건에 걸리게 했다 —
     * {@code users_account}/{@code quizzes}를 조인하지 않는다.
     */
    Optional<QuizLike> findByUserAccount_IdAndQuiz_Id(Long userAccountId, Long quizId);

    /**
     * 문제 하나의 좋아요 수 — 토글 응답과 단건 상세가 쓴다.
     *
     * <p>{@code LikedTrue}가 조건에 있는 것이 핵심이다. 취소한 좋아요도 행으로 남으므로
     * (엔티티 javadoc 참고) 이 조건을 빼면 "한 번이라도 좋아요한 사람 수"가 나온다.
     *
     * <p>{@code quiz_id}는 FK 자동 인덱스를 타지만 {@code liked}가 그 인덱스에 없어 행 접근은 발생한다 —
     * 커버링 인덱스가 아니다. 인기 문제일수록 세는 행이 늘어난다는 뜻이지만, 좋아요 수 자체가 사람 수라
     * 폭주하지 않는다. 비정규화 카운터({@code quizzes.like_count})는 같은 사실을 두 곳에 두게 되므로
     * 두지 않기로 한 결정이다 — 다시 꺼내려면 별도 요구사항이 필요하다.
     */
    long countByQuiz_IdAndLikedTrue(Long quizId);

    /**
     * 문제 N건의 좋아요 수를 한 방에 집계한다 — 이력·상세가 항목마다 위 {@code countBy}를 부르면
     * 그대로 N+1 이다. 페이지 항목이 1건이든 20건이든 이 쿼리 1건으로 끝나야 한다.
     * ({@code QuizOptionRepository.findAllByQuiz_IdInOrderBy...}가 먼저 택한 {@code quiz_id IN} 2쿼리
     * 방식과 같은 방법이다.)
     *
     * <p>파생 쿼리로 못 쓰는 이유: 반환이 (quizId, count) 쌍 목록이라 메서드 이름 규칙으로 표현되지
     * 않는다. 별칭 {@code quizId}/{@code likeCount}는 {@link QuizLikeCountView}의 getter 이름과
     * 맞물려 있으니 함께 고칠 것.
     *
     * <p>⚠ <b>좋아요가 하나도 없는 문제는 결과에 아예 나오지 않는다</b>({@code group by}의 성질).
     * 호출부는 없는 키를 0으로 채워야 한다 — {@code Map}으로 접어놓고 {@code getOrDefault(quizId, 0L)}
     * 형태를 쓰지 않으면 이력 화면에서 좋아요 0인 문제가 통째로 NPE 를 낸다.
     *
     * <p>{@code l.quiz.id}는 to-one 의 식별자라 {@code quizzes} 조인 없이 FK 컬럼으로 풀린다.
     */
    @Query("select l.quiz.id as quizId, count(l.id) as likeCount from QuizLike l "
            + "where l.quiz.id in :quizIds and l.liked = true group by l.quiz.id")
    List<QuizLikeCountView> countLikesByQuizIds(@Param("quizIds") Collection<Long> quizIds);

    /**
     * 주어진 문제들 중 이 계정이 <b>지금</b> 좋아요 중인 것의 id 만 추린다 — 위 집계와 짝을 이루는
     * 두 번째 쿼리이며, 이력 한 페이지의 좋아요 조회는 이 둘로 끝난다.
     *
     * <p>엔티티가 아니라 id 목록인 이유: 호출부는 항목별 {@code liked} 판정에 "이 목록에 있는가"만
     * 쓴다. 조회 결과가 곧 켜진 것들이므로 <b>목록에 없으면 {@code false}</b>다 — 좋아요한 적 없는
     * 경우와 취소한 경우가 여기서 같은 상태로 합쳐지고, 그게 응답 계약과 일치한다.
     *
     * <p>{@code l.liked = true} 조건이 빠지면 취소한 문제까지 켜진 것으로 보이게 된다.
     * {@code uk_quizzes_like_account_quiz}가 선행 컬럼으로 계정을 받아 이 조회의 진입을 좁힌다.
     */
    @Query("select l.quiz.id from QuizLike l "
            + "where l.userAccount.id = :userAccountId and l.quiz.id in :quizIds and l.liked = true")
    List<Long> findLikedQuizIds(@Param("userAccountId") Long userAccountId,
            @Param("quizIds") Collection<Long> quizIds);
}
