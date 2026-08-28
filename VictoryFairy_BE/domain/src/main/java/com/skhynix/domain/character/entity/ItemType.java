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
 * 캐릭터 아이템의 부위(레이어) 코드 테이블. {@code QuizType}·{@code GameStatus}·{@code Position} 과 같은
 * 계열로, 유형은 자바 enum 상수가 아니라 행으로 존재하고 {@link CharacterItem} 이 {@code item_type_id}
 * FK 로 참조한다.
 *
 * <p>현재 값 3종(에셋 디렉터리 구조 그대로): {@code 의상}({@code cloth}) · {@code 모자}({@code head}) ·
 * {@code 소품}({@code item}).
 *
 * <p><b>이 테이블은 단순한 분류표가 아니라 착용 규칙의 단위다.</b> "한 계정은 같은 부위 아이템을 하나만
 * 켤 수 있다"가 토글 API 의 유일한 배타 조건이므로, 행을 추가하면 그 순간 새 레이어가 하나 더 생긴다.
 * 뒤집어 말하면 {@code name} 은 <b>닫힌 집합이 아니다</b> — 3종만 분기하고 default 를 두지 않는 코드는
 * 깨진다.
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

    /**
     * 부위명. 시드가 이 값 기준 anti-join 으로 재실행 안전을 확보하고, 빈 DB 에 파드가 동시에 뜰 때
     * 같은 이름이 두 행으로 갈라지는 것을 UNIQUE 가 <b>만들어지는 순간</b> 막는다(둘째 INSERT 가
     * Duplicate entry 로 죽고 재시작하면 앞선 행이 보여 통과한다 — 조용한 중복 대신 시끄러운 실패).
     * 같은 이름이 두 행이면 착용 배타 조건이 부위별로 갈라져 모자를 두 개 쓸 수 있게 된다.
     */
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
