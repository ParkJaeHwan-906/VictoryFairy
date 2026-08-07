package com.skhynix.domain.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(
        name = "quiz_type",
        // 제약 이름을 명시한다(@Column(unique=true) 의 Hibernate 자동 생성명 UK... 대신) —
        // 나중에 손으로 도는 DDL 과 같은 이름을 써야 "이미 걸렸는지"를 이름으로 확인할 수 있다.
        // uk_game_statuses_name 과 같은 성격이다.
        uniqueConstraints = @UniqueConstraint(name = "uk_quiz_type_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 유형명. 이 테이블을 lookup-or-create 로 채우고({@code QuizTypeRepository.findByName}) 그 반환형이
     * {@code Optional} 이라, 같은 이름이 두 행이면 조회가 예외로 죽는다. UNIQUE 는 그 상태를 <b>만들어지는
     * 순간</b> 막는다 — 빈 DB 에 파드 여러 개가 동시에 뜨면 각자의 anti-join 이 서로의 미커밋 INSERT 를
     * 못 봐 같은 이름을 두 번 넣는데, 제약이 있으면 두 번째가 {@code Duplicate entry} 로 죽어 기동이
     * 실패하고(= 조용한 중복 대신 시끄러운 실패) 재시작하면 앞선 행이 보여 통과한다.
     * {@code GameStatus.name}·{@code Team.code} 와 같은 성격이다.
     *
     * <p>⚠ {@code ddl-auto=update} 는 <b>이미 존재하는 테이블에 UNIQUE 를 추가하지 않는다</b>(domain 실측).
     * {@code quiz_type} 은 아직 어느 환경에도 없어 이 선언만으로 붙지만, 테이블이 한 번 생긴 뒤에는
     * 1회성 DDL 을 손으로 돌아야 한다.
     */
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
