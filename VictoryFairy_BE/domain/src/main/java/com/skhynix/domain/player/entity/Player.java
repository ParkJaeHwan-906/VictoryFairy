package com.skhynix.domain.player.entity;

import com.skhynix.domain.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "players")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "average", nullable = false)
    private double average;

    /**
     * KBO 공식 사이트 playerId. py-collector 로스터·박스스코어 적재 공통의 소스 자연키(UNIQUE).
     * 네이버 record API 의 pcode 도 실측상 같은 값(2026-07 교집합 228명 전수 일치)이라
     * 단일 컬럼으로 통합했다(구 naver_pcode 컬럼 폐기 — infra/sql/migrate-position-records.sql).
     */
    @Column(name = "kbo_player_id", length = 16, unique = true)
    private String kboPlayerId;

    /**
     * 등번호. KBO 등록명단발로 py-collector 가 매일 최신화한다. 미배정(일부 육성선수)이
     * 있고 시즌 중 변경도 잦아 nullable. '0'·'00' 구분과 선행 0 보존을 위해 문자열이다.
     */
    @Column(name = "uniform_number", length = 4)
    private String uniformNumber;

    /**
     * KBO 공식 포지션 구분(4그룹). 등록명단발이라 1군 이력이 없는 행은 null 일 수 있다.
     * STRING 매핑은 py-collector 와의 계약 — {@link PositionGroup} javadoc 참고.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "position_group", length = 16)
    private PositionGroup positionGroup;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Player(Team team, String name, double average, String kboPlayerId,
                   String uniformNumber, PositionGroup positionGroup) {
        this.team = team;
        this.name = name;
        this.average = average;
        this.kboPlayerId = kboPlayerId;
        this.uniformNumber = uniformNumber;
        this.positionGroup = positionGroup;
    }
}
