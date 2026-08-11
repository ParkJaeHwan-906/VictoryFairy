package com.skhynix.domain.game.entity;

import com.skhynix.domain.stadium.entity.Stadium;
import com.skhynix.domain.team.entity.Team;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
//
// CHECK 제약 2개는 이닝 컬럼이 nullable 인 것과 충돌하지 않는다 — SQL 의 CHECK 는 결과가 UNKNOWN 이면
// 통과시키므로 NULL 행은 그대로 허용된다. 제약 이름을 명시하는 이유는 UNIQUE 와 같다: 이름을
// Hibernate 자동 생성에 맡기면 손으로 도는 1회성 DDL 과 이름이 어긋나 "이미 걸렸는지" 를 확인할 수 없다.
//
// ⚠ ddl-auto=update 가 이 선언을 기존 테이블에 반영하는지는 환경에 따라 갈렸다(2026-08-11 실측).
//   prod  — 이미 있던 games 에 ck_games_current_inning·ck_games_inning_half 가 둘 다 걸렸다.
//           games 의 CREATE_TIME 이 파드 기동 직후로 갱신됐고, 배포 파이프라인에 SQL 적용 경로가
//           없으며 손으로 돈 적도 없다.
//   devdb·로컬 — 같은 코드로 add column 만 나가고 두 제약 다 걸리지 않았다. 대신 Hibernate 가
//           @Enumerated(ORDINAL) 컬럼에 자동으로 붙이는 서수 범위 검사가 add column 에 딸려
//           `games_chk_N`(자동 생성명) 으로 남았다.
//   갈린 원인은 규명하지 못했다. 그래서 "선언했으니 걸려 있다" 도, "update 는 안 건다" 도 전제로
//   삼지 말고, 환경마다 information_schema 로 실제 상태를 조회해 판단할 것.
//   확인·정리 절차는 infra/sql/migrate-game-inning.sql 에 있다.
@Entity
@Table(name = "games", indexes = {
        @Index(name = "idx_games_game_date", columnList = "game_date")
}, check = {
        @CheckConstraint(name = "ck_games_current_inning", constraint = "current_inning BETWEEN 1 AND 11"),
        @CheckConstraint(name = "ck_games_inning_half", constraint = "inning_half IN (0, 1)")
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

    // 진행 중인 경기의 현재 이닝과 초/말. 진행 중이 아닌 경기(예정·종료·취소·무승부)는 둘 다 null 이라
    // nullable 이며, cancel_reason 과 마찬가지로 채우는 주체는 py-collector 다(이 앱에 games 쓰기 경로 없음).
    @Column(name = "current_inning", columnDefinition = "TINYINT", nullable = true)
    private Integer currentInning;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "inning_half", columnDefinition = "TINYINT", nullable = true)
    private InningHalf inningHalf;

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
            Integer currentInning, InningHalf inningHalf, String naverGameId) {
        this.gameDate = gameDate;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.stadium = stadium;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.gameStatus = gameStatus;
        this.cancelReason = cancelReason;
        this.currentInning = currentInning;
        this.inningHalf = inningHalf;
        this.naverGameId = naverGameId;
    }
}
