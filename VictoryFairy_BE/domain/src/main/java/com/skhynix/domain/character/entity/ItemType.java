package com.skhynix.domain.character.entity;

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
 * 캐릭터 아이템의 부위(레이어) 코드 테이블 — {@code 의상}·{@code 모자}·{@code 소품}.
 * {@code QuizType}·{@code GameStatus} 와 같은 계열이다.
 *
 * <p><b>착용 규칙의 단위가 이 행이다</b> — "한 계정은 같은 부위 아이템을 하나만 켠다"가 토글의 유일한
 * 배타 조건이라, 행을 추가하면 그 순간 레이어가 하나 더 생긴다. 뒤집어 말하면 {@code name} 은
 * <b>닫힌 집합이 아니므로</b> 3종만 분기하고 default 를 두지 않는 코드는 깨진다.
 */
@Entity
@Table(
        name = "item_types",
        uniqueConstraints = @UniqueConstraint(name = "uk_item_types_name", columnNames = "name"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 같은 이름이 두 행이면 착용 배타 조건이 부위별로 갈라져 모자를 두 개 쓸 수 있게 된다.
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private ItemType(String name) {
        this.name = name;
    }
}
