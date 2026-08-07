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
 * <p>⚠ <b>한 사용자가 같은 문제에 여러 번 제출하는 것을 스키마가 막지 않는다.</b> 재제출을 허용할지
 * (허용한다면 새 행을 쌓을지, 같은 행을 갱신할지)가 정해지지 않아 UNIQUE 제약을 걸지 않았다. 1회 제출로
 * 확정되면 {@code (user_account_id, quiz_id)} UNIQUE 를 추가한다. {@code updated_at}을 둔 것은 후자(같은
 * 행 갱신)를 열어두기 위해서다.
 */
@Entity
@Table(name = "quiz_users_submit")
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

    // CASCADE — 보기가 사라지면 "그 보기를 골랐다"는 기록도 함께 사라져야 함
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submit_option_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizOption submitOption;

    // 제출 시점에 확정한 정답 여부(클래스 javadoc 참고 — 사후 재계산하지 않는다)
    @Column(name = "is_answer", columnDefinition = "TINYINT", nullable = false)
    private boolean isAnswer;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private QuizUserSubmit(UserAccount userAccount, Quiz quiz, QuizOption submitOption,
            boolean isAnswer) {
        this.userAccount = userAccount;
        this.quiz = quiz;
        this.submitOption = submitOption;
        this.isAnswer = isAnswer;
    }
}
