package com.skhynix.domain.game.entity;

import com.skhynix.domain.stadium.entity.Stadium;
import com.skhynix.domain.team.entity.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

// idx_games_game_date(game_date): 필터·정렬 컬럼이 동일해 단일 컬럼 인덱스만으로 레인지 스캔+정렬이
// 해결된다(GameRepository의 반개구간 조회). home/away team·stadium·game_status는 @EntityGraph 조인
// 대상이라 넣지 않음 — 제거하면 날짜별 조회가 games 풀스캔 + filesort로 떨어진다.
@Entity
@Table(name = "games", indexes = {
        @Index(name = "idx_games_game_date", columnList = "game_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "game_date", nullable = false)
    private LocalDateTime gameDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id", nullable = false)
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id", nullable = false)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "stadium_id", nullable = true)
    private Stadium stadium;

    @Column(name = "home_score", nullable = true)
    private Integer homeScore;

    @Column(name = "away_score", nullable = true)
    private Integer awayScore;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_status_id", nullable = false)
    private GameStatus gameStatus;

    // 취소 사유(예: 폭염취소·우천취소). gameStatus 가 CANCELED 일 때만 채워지고 그 외에는 null 이다.
    // 상태를 코드 테이블에 더 쪼개 담지 않는 이유: 사유는 닫힌 집합이 아니라 늘어나는데,
    // game_statuses 에 섞으면 CANCELED 판정 코드가 사유 종류마다 깨진다.
    // 값의 출처는 KBO 공식 일정표다 — 네이버 스케줄 API 는 취소를 "경기취소" 로만 알려줘
    // 사유가 구분되지 않는다. 채우는 주체는 py-collector 이고 이 앱에 쓰기 경로는 없다.
    @Column(name = "cancel_reason", length = 50, nullable = true)
    private String cancelReason;

    // 네이버 스포츠 gameId — py-collector가 재실행해도 중복 없이 upsert하기 위한 소스 자연키(UNIQUE)
    @Column(name = "naver_game_id", length = 20, unique = true)
    private String naverGameId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Game(LocalDateTime gameDate, Team homeTeam, Team awayTeam, Stadium stadium,
            Integer homeScore, Integer awayScore, GameStatus gameStatus, String cancelReason,
            String naverGameId) {
        this.gameDate = gameDate;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.stadium = stadium;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.gameStatus = gameStatus;
        this.cancelReason = cancelReason;
        this.naverGameId = naverGameId;
    }
}
