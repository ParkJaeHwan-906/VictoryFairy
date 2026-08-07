package com.skhynix.domain.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 퀴즈 유형 코드 테이블. 유형은 자바 enum 상수가 아니라 {@code quiz_type} 테이블의 행({@code name})으로
 * 존재하며, {@link Quiz}가 {@code quiz_type_id} FK로 참조한다({@code GameStatus}·{@code Position}과 같은
 * 코드 테이블 계열).
 *
 * <p>현재 값 2종(사용자 확정):
 * <ul>
 *   <li>{@code 객관식} — 보기 여러 개 중 하나를 고른다. {@link QuizOption#getOption()}이 보기 번호.</li>
 *   <li>{@code O/X} — 보기가 두 개뿐인 특수 형태. 보기 번호를 {@code 0}(X)/{@code 1}(O)로 표기한다.</li>
 * </ul>
 * 유형이 늘어나면 이 테이블에 행만 추가하면 되고 코드 변경·배포는 필요 없다. 뒤집어 말하면
 * {@code name}은 <b>닫힌 집합이 아니므로</b> 위 2종만 분기하고 default를 두지 않는 코드는 깨질 수 있다.
 *
 * <p>테이블명이 단수({@code quiz_type})인 것은 사용자가 제공한 스키마 그대로다 — {@code user_support_team}
 * 과 같은 명시적 예외이며, 다른 새 엔티티에 단수형을 따라 쓰지 말 것.
 */
@Entity
@Table(name = "quiz_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 10, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private QuizType(String name) {
        this.name = name;
    }
}
