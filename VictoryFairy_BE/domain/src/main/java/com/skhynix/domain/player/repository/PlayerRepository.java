package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    /**
     * KBO 선수 ID 로 조회 — 퀴즈 적재가 S3 후보의 {@code subject.playerIds}(KBO id, 위키
     * {@code players/{id}.md} 파일명과 같은 축)를 FK 로 해석할 때 쓴다.
     * {@code players.kbo_player_id} UNIQUE 가 {@code Optional} 반환을 뒷받침한다.
     */
    Optional<Player> findByKboPlayerId(String kboPlayerId);

    List<Player> findAllByOrderByNameAsc();

    // Team_Id로 끊어 team.id로만 해석 — team 조인 없이 FK 컬럼만 조건(team은 LAZY라 초기화 안 됨)
    List<Player> findAllByTeam_IdOrderByNameAsc(Long teamId);

    // LIKE '%name%' 부분 일치. 선행 와일드카드라 인덱스를 못 타지만 선수 테이블 규모(수백 행)라 허용
    List<Player> findAllByNameContainingOrderByNameAsc(String name);

    List<Player> findAllByTeam_IdAndNameContainingOrderByNameAsc(Long teamId, String name);
}
