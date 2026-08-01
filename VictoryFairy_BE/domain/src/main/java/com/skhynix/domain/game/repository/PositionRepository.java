package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.Position;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {

    Optional<Position> findByName(String name);
}
