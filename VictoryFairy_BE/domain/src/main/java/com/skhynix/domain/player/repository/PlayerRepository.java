package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findAllByOrderByNameAsc();

    // Team_Id로 끊어 team.id로만 해석 — team 조인 없이 FK 컬럼만 조건(team은 LAZY라 초기화 안 됨)
    List<Player> findAllByTeam_IdOrderByNameAsc(Long teamId);

    // 응원 행에서 FK id만 모아 batch 조회(N+1 방지) — SupportService의 응원 선수 응답용
    List<Player> findAllByIdInOrderByNameAsc(Collection<Long> ids);

    // LIKE '%name%' 부분 일치. 선행 와일드카드라 인덱스를 못 타지만 선수 테이블 규모(수백 행)라 허용
    List<Player> findAllByNameContainingOrderByNameAsc(String name);

    List<Player> findAllByTeam_IdAndNameContainingOrderByNameAsc(Long teamId, String name);
}
