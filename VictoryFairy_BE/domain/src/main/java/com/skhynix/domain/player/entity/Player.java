package com.skhynix.domain.player.entity;

import com.skhynix.domain.team.entity.Team;
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
     * 선수 코드 — KBO 공식 사이트 playerId. py-collector 적재(1군 로스터·라인업)가 재실행에도
     * 중복 없이 upsert 하기 위한 소스 자연키(UNIQUE)이며, 서비스 로직에서는 몰라도 된다.
     *
     * <p>네이버 record API 의 pcode 도 같은 값이다(2026-07 박스스코어·로스터 교집합 전수 일치
     * 실측). 초기에는 다른 체계로 보고 naver_pcode 컬럼을 따로 뒀으나 동일 값으로 확인되어
     * 이 컬럼 하나로 통합했다.
     */
    @Column(name = "kbo_player_id", length = 16, unique = true)
    private String kboPlayerId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Player(Team team, String name, double average, String kboPlayerId) {
        this.team = team;
        this.name = name;
        this.average = average;
        this.kboPlayerId = kboPlayerId;
    }
}
