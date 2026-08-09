package com.skhynix.domain.quiz.entity;

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
 * {@link Quiz} 한 건의 보기 하나.
 *
 * <p>{@code option}은 화면에 보여줄 <b>보기 번호</b>다. 정렬·표기의 근거가 행의 생성 순서나 PK가 아니라
 * 이 값이라는 뜻이라, 보기를 다시 만들어도 번호만 유지하면 {@link Quiz#getAnswer()}가 가리키는 정답이
 * 따라 깨지지 않는다. O/X 유형은 {@code 0}(O)/{@code 1}(X) 두 행으로 표현한다 — S3 후보 계약의
 * 보기 순서(A=O→0, B=X→1)를 그대로 따른 것이다. 종전 "0=X, 1=O" 서술은 계약과 반대라 정정했다
 * ({@link Quiz#getAnswer()} javadoc·응답 DTO 와 표기 통일).
 *
 * <p>⚠ 컬럼명 {@code option}은 <b>MySQL 예약어</b>라 백틱으로 감싸야 한다(실측: 백틱 없이
 * {@code CREATE TABLE t (option TINYINT)} 는 ERROR 1064). {@code @Column(name = "`option`")}의 백틱은
 * 오타가 아니므로 지우지 말 것 — 지우면 Hibernate가 만드는 DDL이 문법 오류로 실패한다.
 *
 * <p>같은 문제 안에서 번호가 겹치면 안 되지만 UNIQUE 제약은 걸지 않았다 — 사용자가 준 스키마에 없고,
 * 보기 세트를 통째로 갈아끼우는(전체 삭제 후 재삽입) 편집 방식과 충돌할 수 있어 쓰기 경로가 지킬 정책으로
 * 남긴다. 필요해지면 {@code (quiz_id, option)} UNIQUE 를 추가하면 된다.
 */
@Entity
@Table(name = "quiz_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // CASCADE — 보기는 문제에 완전히 종속돼 문제가 사라지면 함께 사라져야 한다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Quiz quiz;

    @Column(name = "contents", columnDefinition = "TEXT", nullable = false)
    private String contents;

    // 백틱 필수 — option 은 MySQL 예약어다(클래스 javadoc 참고)
    @Column(name = "`option`", columnDefinition = "TINYINT", nullable = false)
    private Integer option;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private QuizOption(Quiz quiz, String contents, Integer option) {
        this.quiz = quiz;
        this.contents = contents;
        this.option = option;
    }
}
