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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
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
 * KBO 1군 등록명단 일별 스냅샷 — "이 날짜에 이 선수가 이 팀 1군에 등록돼 있었다".
 *
 * <p>py-collector 의 registrations 잡이 매일(11:00 KST) KBO 공식 등록명단
 * (Player/Register.aspx)을 크롤해 적재한다. {@code (registration_date, player_id)} 가
 * 소스 자연키(UNIQUE)로, 같은 날 재실행은 upsert 로 멱등이다. 선수 데이터와 마찬가지로
 * 수집기가 소유하므로 서비스 쪽 쓰기 경로는 두지 않는다.
 *
 * <p>현재 소속(오늘 기준 명단)은 {@code Player.team} 으로 충분하고, 이 테이블은 날짜별
 * 이력 조회용이다 — 특정 일자의 등록 명단, 선수의 등록/말소 구간 파생 등.
 */
@Entity
@Table(name = "registrations", uniqueConstraints = @UniqueConstraint(
        name = "uq_registrations_date_player", columnNames = {"registration_date", "player_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 등록명단 조회 기준일(KBO 사이트의 등록일). */
    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Registration(LocalDate registrationDate, Team team, Player player) {
        this.registrationDate = registrationDate;
        this.team = team;
        this.player = player;
    }
}
