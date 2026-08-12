package com.skhynix.domain.quiz.entity;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 사용자가 어떤 {@link Quiz}에 어떤 보기를 제출했고 그게 정답이었는지를 남기는 기록.
 *
 * <p>{@code isAnswer}는 제출 시점에 <b>확정해 저장하는 값</b>이다. {@code submitOption.option}과
 * {@code quiz.answer}를 비교하면 매번 다시 구할 수는 있지만, 문제의 정답이 나중에 정정되면 과거 채점
 * 결과까지 소급해 뒤집힌다 — 이미 준 점수와 어긋나므로 제출 당시의 판정을 그대로 보존한다.
 *
 * <p>테이블명이 단수형 어미({@code quiz_users_submit})인 것은 사용자가 제공한 스키마 그대로다
 * ({@code user_support_team}과 같은 명시적 예외). 사용자 초안의 컬럼명 {@code Field}는 의미가 드러나지
 * 않아 사용자 설명("정답 유/무")대로 {@code is_answer}로 명명했다.
 *
 * <p><b>{@code (user_account_id, quiz_id)} UNIQUE — 같은 문제에는 1회만 제출할 수 있다.</b>
 * "이미 푼 문제는 다시 풀 수 없다"가 확정 규칙이고 제출 기록이 점수 적립({@code users_bq.score})의
 * 근거인 이상, 중복 제출(=점수 이중 적립) 차단은 앱 로직이 아니라 DB 가 보증해야 한다 — 동시 요청
 * 2건이 둘 다 existsBy 검사를 통과하는 race 를 막는 것은 이 제약뿐이다. {@code updated_at}은 정정
 * 등으로 같은 행을 갱신할 가능성을 위해 남겨둔다(새 행 재제출은 이 제약이 막는다).
 */
// uk_quiz_users_submit_account_quiz(user_account_id, quiz_id): 중복 제출 차단(UNIQUE) + 판정 조회
// (findByUserAccount_IdAndQuiz_Id / existsByUserAccount_IdAndQuiz_Id)이 인덱스 하나로 단건 조회가 된다.
// 예전의 같은 컬럼 쌍 일반 인덱스(idx_...)를 승격한 것 — 따로 추가하면 같은 컬럼 쌍에 인덱스가 둘이 된다.
// 없으면 InnoDB 가 FK 마다 자동으로 만드는 단일 컬럼 인덱스 둘로 index_merge intersect 를 돌리는데,
// quiz_id 쪽 스캔 길이가 "그 문제를 푼 사람 수"에 비례해 자란다 — 즉 인기 문제일수록 제출 경로가 느려진다.
// (실측: 제출 20만 행/사용자 2000명 기준 intersect 100+1235행 0.16ms → 인덱스 단건 조회 0.006ms,
//  existsBy 는 커버링 인덱스로 테이블 접근 0회. 제거하지 말 것)
// 선행 컬럼이 user_account_id 인 이유: 둘 다 등치라 이 질의만 보면 순서가 무관하지만, 앞으로 붙을
// "내 제출 이력"(user_account_id 단독)을 같은 인덱스가 받는다. 반대편 "문제별 정답률"(quiz_id 단독)은
// FK 자동 인덱스가 이미 받는다.
// ⚠ ddl-auto=update 는 기존 테이블에 UNIQUE 를 추가하지 않는다(game_statuses 실측) — 테이블이 이미
//   생성된 환경(dev)은 infra/sql/migrate-quiz-ingest.sql 을 1회 수동 실행해야 한다.
@Entity
@Table(name = "quiz_users_submit", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quiz_users_submit_account_quiz",
                columnNames = {"user_account_id", "quiz_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizUserSubmit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 제출 기록은 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 됨
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    // CASCADE — 문제가 사라지면 그 문제에 대한 제출 기록도 함께 사라져야 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Quiz quiz;

    /**
     * 제출한 보기.
     *
     * <p>⚠ <b>이 CASCADE 는 {@link QuizOption} 문서가 열어둔 "보기 세트 통째 교체(전체 삭제 후 재삽입)"
     * 편집 방식과 정면으로 충돌한다.</b> 문제는 그대로 두고 {@code quiz_options} 행만 지우면 그 문제의
     * 제출 기록이 전부 함께 지워진다(실측: 제출 2000행이 있는 문제의 보기만 삭제 → 제출 0행). 잃는 것은
     * 통계뿐이 아니다 — 중복 제출 차단이 이 테이블 조회로만 이뤄지므로 <b>이미 푼 문제를 다시 풀고 점수를
     * 다시 받을 수 있게 된다.</b>
     *
     * <p>{@code Quiz.answer} 를 보기 FK 가 아닌 <b>번호 값</b>으로 둔 것이 정확히 이 취약함을 피하려는
     * 설계인데, 이 필드만 보기 행을 FK 로 붙잡아 같은 취약함을 되살린다.
     *
     * <p><b>그럼에도 FK + CASCADE 를 유지하기로 했다</b>(사용자 결정) — 보기를 편집·삭제하는 경로가 아직
     * 없어서 지금은 발생할 수 없는 문제이고, 대비책을 미리 넣으면 쓰지도 않을 복잡도만 남는다.
     * <b>재검토 시점은 「보기 편집 기능을 만들 때」로 고정</b>한다. 그때 아래에서 고르면 된다:
     * <ul>
     *   <li>번호 값으로 전환({@code Quiz.answer} 와 대칭) — 제출 기록이 자립하고 FK 자체가 사라진다.
     *       그 대신 "고른 보기의 텍스트"는 복원할 수 없다.</li>
     *   <li>{@code OnDeleteAction.SET_NULL} + nullable — 보기만 지워도 제출 행과 {@code isAnswer} 는
     *       남고, 문제 통째 삭제는 {@code quiz_id} CASCADE 로 그대로 정리된다(둘 다 실측 확인).</li>
     *   <li>{@code RESTRICT} 는 <b>쓸 수 없다</b> — InnoDB 가 {@code quizzes} 삭제를 {@code quiz_options}
     *       쪽으로 먼저 연쇄시켜서 <b>문제 통째 삭제까지 ERROR 1451 로 실패한다</b>(실측).</li>
     * </ul>
     * ⚠ 그때까지의 임시 방편은 <b>보기 수정을 DELETE+INSERT 가 아니라 UPDATE 로만 하는 것</b>이다
     * (행 id 가 유지되면 FK 가 안 깨진다). 다만 이건 스키마가 강제하지 않는 규율이고, JPA 의
     * {@code @OneToMany(orphanRemoval = true)} 에 새 리스트를 넣는 흔한 구현이 내부적으로 DELETE+INSERT 라
     * 의식하지 않으면 그냥 밟는다. 보기 개수를 줄이는 편집은 UPDATE 로 해결되지도 않는다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submit_option_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizOption submitOption;

    // 제출 시점에 확정한 정답 여부(클래스 javadoc 참고 — 사후 재계산하지 않는다)
    @Column(name = "is_answer", columnDefinition = "TINYINT", nullable = false)
    private boolean isAnswer;

    /**
     * 그 문제를 푼 시점의 경기 이닝 — {@code games.current_inning}을 <b>그대로 복사한 값</b>이며
     * 앱이 보정·반올림하지 않는다. 정의역도 원천과 같은 1~11({@code ck_games_current_inning}).
     * 초/말({@code inning_half})은 담지 않는다.
     *
     * <p><b>nullable 이고 {@code @ColumnDefault}를 두지 않는다.</b> 용도가 통계·분석이라 값 누락이
     * 허용되는데, "이미 행이 있는 테이블에 NOT NULL 컬럼 추가"( {@code UserAccount.point} 선례)가
     * 요구하는 초기값 {@code 0} 은 이닝 정의역 밖이라 기존 제출 전부에 "0회에 풀었다"는 거짓을
     * 남긴다. 이닝 정의역에는 "모름"을 표현할 숫자가 없으므로 NULL 로 표현한다.
     *
     * <p>CHECK 제약도 걸지 않는다 — 값의 출처가 이미 CHECK 로 닫힌 컬럼이고, {@code ddl-auto=update}
     * 가 기존 테이블에 CHECK 를 거는지는 환경마다 갈렸다(2026-08-11 prod/devdb 실측).
     *
     * <p>{@code isAnswer}와 같은 계열의 <b>제출 시점 스냅샷</b>이다 — 그 뒤 경기가 진행돼도 이미
     * 저장된 값은 변하지 않고, 조회 경로가 다시 계산하지도 않는다.
     */
    @Column(name = "inning", columnDefinition = "TINYINT", nullable = true)
    private Integer inning;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // inning 은 빌더 파라미터로 받는다 — uid·타임스탬프처럼 "항상 정해진 초기값에서 시작하는 값"이
    // 아니라 제출 시점에 정해지는 관측값이라, 만드는 쪽이 알고 있는 값을 넣어야 한다(모르면 null).
    @Builder
    private QuizUserSubmit(UserAccount userAccount, Quiz quiz, QuizOption submitOption,
            boolean isAnswer, Integer inning) {
        this.userAccount = userAccount;
        this.quiz = quiz;
        this.submitOption = submitOption;
        this.isAnswer = isAnswer;
        this.inning = inning;
    }
}
