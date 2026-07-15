package com.skhynix.domain.record.entity;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "batter_records")
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

    @Column(name = "game_date", nullable = false)
    private LocalDateTime gameDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bat_result_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private BatResult batResult;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private BatterRecord(Player player, LocalDateTime gameDate, BatResult batResult) {
        this.player = player;
        this.gameDate = gameDate;
        this.batResult = batResult;
    }
}
