package com.skhynix.domain.record.entity;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.player.entity.Player;
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
 * 투수 경기 기록(경기×선수 1행 집계). 원천은 네이버 record API 박스스코어(pitchersBoxscore)이며
 * py-collector 가 멱등 upsert 한다(백필 재실행으로 갱신될 수 있어 updated_at 보유).
 * 스탯 컬럼은 API 결측 대비 전부 nullable(단, seq는 등판 순서를 보장하는 정수형 NOT NULL).
 * 승패/세이브/홀드(decision)는 game_lineups 소관(중복 저장 금지).
 */
@Entity
@Table(name = "pitcher_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_pitcher_records_game_player", columnNames = {"game_id", "player_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PitcherRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Game game;

    @Column(name = "seq", nullable = false)            private int seq;        // 등판 순서(0=선발)
    @Column(name = "ip_display", length = 8, nullable = true) private String ipDisplay; // "6 ⅓"
    @Column(name = "ip_outs", nullable = true)         private Integer ipOuts; // 아웃 수(6⅓=19)
    @Column(name = "batters_faced", nullable = true)   private Integer battersFaced;
    @Column(name = "at_bats", nullable = true)         private Integer atBats;
    @Column(name = "hits", nullable = true)            private Integer hits;
    @Column(name = "runs", nullable = true)            private Integer runs;
    @Column(name = "earned_runs", nullable = true)     private Integer earnedRuns;
    @Column(name = "home_runs", nullable = true)       private Integer homeRuns;
    @Column(name = "walks_hbp", nullable = true)       private Integer walksHbp;
    @Column(name = "strikeouts", nullable = true)      private Integer strikeouts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private PitcherRecord(Player player, Game game, int seq, String ipDisplay, Integer ipOuts,
            Integer battersFaced, Integer atBats, Integer hits, Integer runs, Integer earnedRuns,
            Integer homeRuns, Integer walksHbp, Integer strikeouts) {
        this.player = player;
        this.game = game;
        this.seq = seq;
        this.ipDisplay = ipDisplay;
        this.ipOuts = ipOuts;
        this.battersFaced = battersFaced;
        this.atBats = atBats;
        this.hits = hits;
        this.runs = runs;
        this.earnedRuns = earnedRuns;
        this.homeRuns = homeRuns;
        this.walksHbp = walksHbp;
        this.strikeouts = strikeouts;
    }
}
