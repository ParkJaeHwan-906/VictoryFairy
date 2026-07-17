package com.skhynix.domain.game.entity;

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
 * 경기 상태 코드 테이블. 상태값은 코드 상수가 아니라 {@code game_statuses} 테이블의 행({@code name})으로 존재하며,
 * {@link Game}이 {@code game_status_id} FK로 참조한다.
 *
 * <p>{@code name}으로 들어가는 값과 그 의미:
 * <ul>
 *   <li>{@code SCHEDULED} = 예정</li>
 *   <li>{@code FINISHED}  = 정상 종료(승패 확정)</li>
 *   <li>{@code DRAW}      = 무승부</li>
 *   <li>{@code CANCELED}  = 우천 취소 / 노게임</li>
 * </ul>
 */
@Entity
@Table(name = "game_statuses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private GameStatus(String name) {
        this.name = name;
    }
}
