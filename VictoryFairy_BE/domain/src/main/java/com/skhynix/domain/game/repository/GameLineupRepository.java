package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.GameLineup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameLineupRepository extends JpaRepository<GameLineup, Long> {

    List<GameLineup> findByGameId(Long gameId);

    /** 선발 라인업(타순 1~9 + 선발투수)만 조회. */
    List<GameLineup> findByGameIdAndIsStarterTrue(Long gameId);
}
