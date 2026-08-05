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
 * 경기 상태 코드 테이블 — 상태값은 코드 상수가 아니라 {@code game_statuses} 테이블의 행({@code name})으로
 * 존재하며 {@link Game}이 {@code game_status_id} FK로 참조한다. 네이버 스포츠 API ↔ name 매핑 근거는
 * domain 모듈 문서 참고.
 *
 * <p>취소 판정 주의: 취소된 경기는 {@code statusCode}가 {@code "RESULT"}가 아니라 {@code "BEFORE"}로 오고
 * 점수는 0-0 껍데기다 — {@code statusCode}가 아니라 {@code cancel} 플래그로 판정해야 한다.
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
