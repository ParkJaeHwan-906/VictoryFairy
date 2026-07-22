package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {
}
