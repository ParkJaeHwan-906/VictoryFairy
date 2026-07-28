package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    List<Player> findAllByOrderByNameAsc();

    /**
     * 특정 구단 소속 선수만 name 오름차순으로 조회한다.
     *
     * <p>{@code Team_Id} 로 끊어 쓰면 Spring Data 가 {@code team.id} 로만 해석해, 나중에 {@code Player} 에
     * {@code teamId} 필드가 생기더라도 파싱이 흔들리지 않는다. 조건이 FK 컬럼({@code team_id}) 하나라
     * {@code Team} 을 조인하지 않으며, {@code team} 은 LAZY 라 응답 변환에서도 초기화되지 않는다.
     */
    List<Player> findAllByTeam_IdOrderByNameAsc(Long teamId);
}
