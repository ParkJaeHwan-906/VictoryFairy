package com.skhynix.domain.quiz.repository;

import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * 주어진 문제들 중 이 계정에게 <b>이미 나간 것</b>(행이 있는 것)의 id — {@code GET /today}의
     * 제외 필터다.
     *
     * <p><b>제외 기준은 "행이 있는가" 하나다.</b> 답했는지도, 시한(+8분)이 지났는지도 보지 않는다 —
     * 한 번 받은 문제는 다시 실어 주지 않고(재조회 없음), 답하지 않았다면 그대로 미제출=오답으로
     * 확정된다. 시한은 <b>서빙 판정에서 완전히 빠지고 제출 경로에만 남는다.</b>
     *
     * <p>그래서 판정이 조회 시각에 의존하지 않고({@code created_at} 을 읽지 않는다),
     * {@code uk_quiz_users_submit_account_quiz}(선행 컬럼 {@code user_account_id})만으로
     * <b>커버링</b>이 된다 — 답·시각 컬럼을 읽던 종전 프로젝션은 행 접근이 필요했다.
     */
    @Query("select s.quiz.id from QuizUserSubmit s "
            + "where s.userAccount.id = :userAccountId and s.quiz.id in :quizIds")
    List<Long> findServedQuizIds(@Param("userAccountId") Long userAccountId,
            @Param("quizIds") Collection<Long> quizIds);

    /**
     * "이 사용자가 그 경기의 그 이닝에 이미 세트를 받았는가" — <b>한 이닝에 한 세트</b> 제한의 유일한
     * 판정이다(별도 테이블·컬럼 없이 행 존재로 판정한다).
     *
     * <p>답 여부는 보지 않는다 — 미답 행이든 답한 행이든 "그 이닝에 받았다"는 사실은 같고, 그 이닝에
     * 받은 문제를 전부 풀어 버린 뒤에도 여전히 막힌다.
     *
     * <p>키에 경기가 들어 있어 <b>날짜 조건이 필요 없다</b> — 어제 9회 행은 {@code game_id} 가 달라
     * 오늘 9회를 막지 않는다. 개정 이전에 쌓인 {@code game_id IS NULL} 행은 {@code NULL = ?} 가 참이
     * 아니라 어떤 판정에도 걸리지 않는다(백필하지 않는다).
     *
     * <p>인덱스는 {@code idx_quiz_users_submit_account_game_inning}(엔티티 주석) — 세 컬럼이 전부
     * 등치라 커버링 존재 검사로 끝난다.
     */
    boolean existsByUserAccount_IdAndGame_IdAndInning(Long userAccountId, Long gameId,
            Integer inning);

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
     * <b>한 경기</b>에서 이 계정이 받은 행 전부(이닝별 결산 조회). 페이징하지 않는다 — 상한이 경기
     * 하나이므로 최대 11이닝 × 서빙 상한(기본 20)이고, 이닝 축을 통째로 그리는 화면이라 잘라 줄 수
     * 없다.
     *
     * <p><b>진입 축이 {@code (user_account_id, game_id)} 라 그대로
     * {@code idx_quiz_users_submit_account_game_inning} 의 선행 두 컬럼이다</b> — 이 조회를 위해 인덱스를
     * 새로 만들 이유가 없다(회차 제한 검사가 쓰는 그 인덱스다).
     *
     * <p>연관 경로를 {@code s.game.id} 로 끊어 FK 컬럼만 조건에 건다(조인 없음). 개정 이전에 쌓인
     * {@code game_id IS NULL} 행은 {@code NULL = ?} 가 참이 아니라 자연히 빠진다 — 백필하지 않는다.
     *
     * <p>to-one 연관 셋({@code quiz}·{@code quiz.quizType}·{@code submitOption})을 fetch join 하는 이유는
     * {@code open-in-view: false} 다 — 응답 DTO 가 문제 지문·유형명·내가 고른 보기를 읽는데, 트랜잭션
     * 밖 LAZY 접근은 예외이고 안이어도 행마다 지연 로딩이면 N+1 이다.
     *
     * <p>⚠ <b>{@code submitOption} 만 {@code left join fetch} 다.</b> inner join 이면 미답 행
     * ({@code submit_option_id IS NULL})이 결과에서 통째로 빠지는데, 미답은 분모에 들어가 오답으로
     * 집계되는 행이라(제품 결정) 빠지는 순간 이닝별 정답률이 조용히 부풀려진다.
     *
     * <p>정렬은 <b>행 {@code id} 오름차순 = 받은 순서</b>다. 이닝 그룹핑은 호출부가 메모리에서 하며,
     * 그 순서가 이닝 안의 문제 순서로 그대로 이어진다({@code order by} 에 이닝을 넣어도 결과 집합은
     * 같지만, 정렬 축이 하나면 "받은 순서"라는 뜻이 흐려지지 않는다).
     */
    @Query("select s from QuizUserSubmit s "
            + "join fetch s.quiz q join fetch q.quizType left join fetch s.submitOption "
            + "where s.userAccount.id = :userAccountId and s.game.id = :gameId "
            + "order by s.id asc")
    List<QuizUserSubmit> findGameSubmissions(@Param("userAccountId") Long userAccountId,
            @Param("gameId") Long gameId);
}
