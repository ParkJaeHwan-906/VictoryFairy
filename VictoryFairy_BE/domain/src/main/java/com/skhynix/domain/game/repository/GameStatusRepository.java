package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.GameStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameStatusRepository extends JpaRepository<GameStatus, Long> {

    Optional<GameStatus> findByName(String name);
}
