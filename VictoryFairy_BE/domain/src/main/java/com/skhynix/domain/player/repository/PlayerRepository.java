package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import java.util.Collection;
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

    /**
     * 주어진 id 집합에 해당하는 선수를 name 오름차순으로 조회한다.
     *
     * <p>응원 선수 응답을 만들 때 쓴다. {@code UserSupportPlayer.player} 는 LAZY 라 응원 행마다
     * {@code getPlayer().getName()} 을 부르면 선수 수만큼 조회가 나가므로, 응원 행에서 FK 값(프록시 id
     * 접근은 초기화를 유발하지 않는다)만 모아 이 메서드로 한 번에 가져온다.
     *
     * <p>정렬을 DB 에 맡기는 것은 {@code findAllByOrderByNameAsc} 와 같은 이유다 — 정렬 기준이 앱과 DB
     * 두 곳으로 갈라지지 않게 한다.
     */
    List<Player> findAllByIdInOrderByNameAsc(Collection<Long> ids);
}
