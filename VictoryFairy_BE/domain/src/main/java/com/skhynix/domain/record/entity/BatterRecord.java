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
 * 타자 경기 기록(경기×선수 1행 집계). 원천은 네이버 record API 박스스코어(battersBoxscore)이며
 * py-collector 가 멱등 upsert 한다(백필 재실행으로 갱신될 수 있어 updated_at 보유).
 * 스탯 컬럼은 API 결측 대비 전부 nullable. 타순·포지션·선발 여부는 game_lineups 소관(중복 저장 금지).
 */
@Entity
@Table(name = "batter_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_batter_records_game_player", columnNames = {"game_id", "player_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatterRecord {

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

    @Column(name = "at_bats", nullable = true)     private Integer atBats;
    @Column(name = "runs", nullable = true)        private Integer runs;
    @Column(name = "hits", nullable = true)        private Integer hits;
    @Column(name = "home_runs", nullable = true)   private Integer homeRuns;
    @Column(name = "rbi", nullable = true)         private Integer rbi;
    @Column(name = "walks", nullable = true)       private Integer walks;
    @Column(name = "strikeouts", nullable = true)  private Integer strikeouts;
    @Column(name = "stolen_bases", nullable = true) private Integer stolenBases;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private BatterRecord(Player player, Game game, Integer atBats, Integer runs, Integer hits,
            Integer homeRuns, Integer rbi, Integer walks, Integer strikeouts, Integer stolenBases) {
        this.player = player;
        this.game = game;
        this.atBats = atBats;
        this.runs = runs;
        this.hits = hits;
        this.homeRuns = homeRuns;
        this.rbi = rbi;
        this.walks = walks;
        this.strikeouts = strikeouts;
        this.stolenBases = stolenBases;
    }
}
