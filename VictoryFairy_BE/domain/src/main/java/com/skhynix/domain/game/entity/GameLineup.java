package com.skhynix.domain.game.entity;

import com.skhynix.domain.player.entity.Player;
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
 * 경기별 출전 명단(교체 포함). 원천은 네이버 record API 박스스코어이며 py-collector 가 적재한다.
 *
 * <p>선발 라인업(타순 1~9 + 선발투수)만 필요하면 {@code isStarter=true} 로 조회한다.
 * <ul>
 *   <li>{@code batOrder} — 타순 1~9. 타석에 서지 않은 투수는 NULL.</li>
 *   <li>{@code position} — 포지션 표기(중/포/지/투 …). 대타는 "타", 대주자는 "주"로 온다.</li>
 *   <li>{@code decision} — 투수 한정 W(승)/L(패)/S(세이브)/H(홀드). 그 외 NULL.</li>
 * </ul>
 * 한 경기에 같은 선수는 한 행({@code game_id, player_id} UNIQUE).
 */
@Entity
@Table(name = "game_lineups", uniqueConstraints = @UniqueConstraint(
        name = "uq_game_lineups_game_player", columnNames = {"game_id", "player_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameLineup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    @Column(name = "bat_order")
    private Integer batOrder;

    @Column(name = "position", length = 10)
    private String position;

    @Column(name = "is_starter", nullable = false)
    private boolean isStarter;

    @Column(name = "decision", length = 2)
    private String decision;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private GameLineup(Game game, Team team, Player player, Integer batOrder,
            String position, boolean isStarter, String decision) {
        this.game = game;
        this.team = team;
        this.player = player;
        this.batOrder = batOrder;
        this.position = position;
        this.isStarter = isStarter;
        this.decision = decision;
    }
}
