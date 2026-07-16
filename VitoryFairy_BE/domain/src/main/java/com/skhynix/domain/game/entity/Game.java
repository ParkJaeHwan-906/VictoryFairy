package com.skhynix.domain.game.entity;

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
@Table(name = "games")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team awayTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Team homeTeam;

    @Column(name = "game_date", nullable = false)
    private LocalDateTime gameDate;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "winner", length = 10)
    private String winner;

    @Column(name = "stadium", length = 50)
    private String stadium;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Game(Team awayTeam, Team homeTeam, LocalDateTime gameDate, Integer awayScore,
                 Integer homeScore, String winner, String stadium) {
        this.awayTeam = awayTeam;
        this.homeTeam = homeTeam;
        this.gameDate = gameDate;
        this.awayScore = awayScore;
        this.homeScore = homeScore;
        this.winner = winner;
        this.stadium = stadium;
    }
}
