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

/**
 * 날짜별 조회 인덱스 {@code idx_games_game_date(game_date)}:
 * 이 테이블의 주 조회 경로는 하루치 반개구간 {@code game_date >= :start and game_date < :end} +
 * {@code order by game_date asc} 하나뿐이다(`GameRepository`). 필터 컬럼과 정렬 컬럼이 동일한
 * {@code game_date} 이므로 단일 컬럼 인덱스만으로 레인지 스캔과 정렬이 동시에 해결된다 —
 * 인덱스가 이미 {@code game_date} 순으로 정렬돼 있어 범위 구간을 순차로 읽는 것이 곧 정렬 결과라
 * filesort 가 사라진다. 복합 인덱스를 만들 이유가 없다: 등치로 앞을 좁힐 선행 컬럼이 없고,
 * 뒤에 컬럼을 붙여도 선행 {@code game_date} 가 범위 조건이라 그 뒤는 정렬·탐색에 쓰이지 못한다.
 * 연관 컬럼(home/away team, stadium, game_status)은 조회 조건이 아니라 {@code @EntityGraph} 조인의
 * 대상이고 각자 FK 인덱스로 접근하므로 여기 넣지 않는다.
 * — 제거하면 날짜별 조회가 games 풀스캔 + filesort 로 떨어진다(시즌이 쌓일수록 악화).
 */
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

    /**
     * 네이버 스포츠 gameId(예: 20260708LGSS02026). py-collector 가 재실행해도 중복 없이
     * upsert 하기 위한 소스 자연키(UNIQUE)이며 더블헤더도 구분한다. 서비스 로직에서는 몰라도 된다.
     */
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
            Integer homeScore, Integer awayScore, GameStatus gameStatus, String naverGameId) {
        this.gameDate = gameDate;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.stadium = stadium;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.gameStatus = gameStatus;
        this.naverGameId = naverGameId;
    }
}
