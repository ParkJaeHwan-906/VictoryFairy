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
 * <p>{@code name}으로 들어가는 값과 그 의미. 괄호 안은 원천인 네이버 스포츠 schedule API 기준 판정 근거다
 * (py-collector 의 docs/data-formats.md "경기 상태 판정" 표):
 * <ul>
 *   <li>{@code SCHEDULED}   = 예정 ({@code statusCode == "BEFORE"})</li>
 *   <li>{@code IN_PROGRESS} = 진행 중 ({@code statusCode == "LIVE"})</li>
 *   <li>{@code FINISHED}    = 정상 종료, 승패 확정 ({@code statusCode == "RESULT"})</li>
 *   <li>{@code DRAW}        = 무승부 ({@code statusCode == "RESULT"} 이고 양 팀 점수가 같음)</li>
 *   <li>{@code CANCELED}    = 우천 취소 / 노게임 ({@code cancel == true})</li>
 * </ul>
 *
 * <p>취소 판정에 주의: 취소된 경기는 {@code statusCode} 가 {@code "RESULT"} 가 아니라 {@code "BEFORE"} 로 오고
 * 점수는 0-0 껍데기다. 그래서 CANCELED 는 {@code statusCode} 가 아니라 {@code cancel} 플래그로 판정해야 한다.
 *
 * <p>아직 반영하지 않은 상태: 네이버는 {@code suspended == true}(서스펜디드 — 중단 후 재개)도 내려준다.
 * 필요해지면 이 테이블에 행만 추가하면 되고 코드 변경·배포는 필요 없다.
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
