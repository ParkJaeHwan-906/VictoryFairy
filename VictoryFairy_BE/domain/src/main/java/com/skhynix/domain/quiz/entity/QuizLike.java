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
 * 사용자가 {@link Quiz}에 남긴 좋아요.
 *
 * <p><b>취소해도 행을 지우지 않고 {@code liked}만 {@code false}로 뒤집는다.</b> 이력 행을 쌓지 않는 이 형태
 * 덕에 "몇 명이 좋아했나"가 요청 횟수로 부풀지 않고, 최초 좋아요 시각({@code createdAt})도 취소·재좋아요를
 * 거쳐 보존된다. 응원({@code user_support_team.oppose})이 먼저 택한 것과 같은 토글 설계다.
 *
 * <p>⚠ <b>그래서 행 수는 좋아요 수가 아니다</b> — 취소된 행({@code liked = false})이 계속 남으므로 집계에는
 * 반드시 {@code liked = true} 조건이 붙어야 한다. 조건을 빠뜨린 {@code count(*)}는 "지금까지 이 문제를 한
 * 번이라도 좋아요한 사람 수"라는 전혀 다른 값을 낸다.
 *
 * <p>플래그 컬럼명이 {@code like}가 아니라 {@code liked}인 것은 <b>예약어 회피</b>다. {@code like}는 MySQL
 * 예약어라 {@code @Column(name = "`like`")}처럼 백틱이 강제되고, 백틱이 빠지는 순간
 * {@code ddl-auto=update}가 만드는 DDL이 문법 오류로 실패해 <b>user 앱이 기동하지 못한다</b>
 * ({@link QuizOption}의 {@code option} 컬럼이 이미 그 지뢰를 안고 있다 — 하나 더 심지 않는다).
 * 테이블명 {@code quizzes_like}는 식별자 전체가 예약어가 아니라 백틱이 필요 없다.
 */
// uk_quizzes_like_account_quiz(user_account_id, quiz_id): 같은 조합의 중복 행 차단이 본래 목적이다.
// 토글이 "찾아서 뒤집고 없으면 만든다"인 이상 동시 요청 2건이 둘 다 조회를 통과하는 race 가 남고,
// 그걸 막는 것은 이 제약뿐이다(앱 로직으로는 못 막는다 — 두 번째 INSERT 가 Duplicate entry 로 튕긴다).
// 덤으로 토글의 단건 조회(findByUserAccount_IdAndQuiz_Id)가 인덱스 하나로 끝난다.
// 선행 컬럼이 user_account_id 인 것은 기존 UNIQUE 3건(제출·응원 구단·응원 선수)이 전부 계정 선두라는
// 일관성 때문이다 — 둘 다 등치 비교라 이 질의만 놓고 보면 순서는 무관하다.
// 별도 인덱스를 두지 않는 이유: 반대편 축(문제별 좋아요 수 = quiz_id 단독)은 InnoDB 가 FK 마다 자동
// 생성하는 단일 컬럼 인덱스가 이미 받는다. "FK 컬럼에 인덱스가 없다"고 판단하지 말 것.
// ⚠ ddl-auto=update 는 이미 존재하는 테이블에 UNIQUE 를 추가하지 않는다(game_statuses 실측) —
//   어떤 이유로든 테이블이 먼저 생긴 환경은 infra/sql/migrate-quiz-like.sql 을 1회 수동 실행해야 한다.
@Entity
@Table(name = "quizzes_like", uniqueConstraints = {
        @UniqueConstraint(name = "uk_quizzes_like_account_quiz",
                columnNames = {"user_account_id", "quiz_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 좋아요는 계정에 완전히 종속돼 계정이 사라지면 함께 사라져도 됨
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserAccount userAccount;

    // CASCADE — 문제가 사라지면 그 문제에 대한 좋아요도 함께 사라져야 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Quiz quiz;

    /**
     * 현재 좋아요 상태(켜짐/꺼짐). 값 영역은 둘뿐이며 "무응답"은 <b>행의 부재</b>로만 표현된다 —
     * 싫어요를 담는 3상태 컬럼이 아니다.
     *
     * <p>{@code @ColumnDefault}를 두지 않는다. 그 장치는 "이미 행이 있는 테이블에 NOT NULL 컬럼을
     * 추가"할 때 기존 행을 채우려고 쓰는 것인데({@code users_account.point} 선례) 이 테이블은 신규라
     * 채워야 할 기존 행이 없다. 신규 행의 값은 언제나 애플리케이션이 정한다.
     */
    @Column(name = "liked", columnDefinition = "TINYINT", nullable = false)
    private boolean liked;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private QuizLike(UserAccount userAccount, Quiz quiz) {
        // liked·타임스탬프는 @Builder 파라미터로 받지 않는다. 행이 생기는 계기는 "좋아요를 눌렀다"
        // 하나뿐이라 켜진 상태로만 태어나야 하고, 빌더로 열어두면 꺼진 채 태어난 행 —— 즉 아무도
        // 누른 적 없는데 createdAt 이 찍힌 행 —— 을 만들 수 있게 된다.
        this.userAccount = userAccount;
        this.quiz = quiz;
        this.liked = true;
    }

    /**
     * 전이를 {@code @Setter}가 아니라 이 메서드로 두는 이유는 호출부가 "다음 값"을 계산하지 않게
     * 하기 위해서다 — 켜기/끄기 판단이 서비스로 새면 같은 판단이 토글 경로마다 복제되고, 그중 하나가
     * 어긋나면 버튼이 한 번에 안 먹는 형태로만 드러난다.
     *
     * <p>행을 지우지 않으므로 {@code createdAt}은 최초 좋아요 시각 그대로 남고 {@code updatedAt}만
     * 갱신된다.
     */
    public void toggle() {
        this.liked = !this.liked;
    }

    public boolean isLiked() {
        return liked;
    }
}
