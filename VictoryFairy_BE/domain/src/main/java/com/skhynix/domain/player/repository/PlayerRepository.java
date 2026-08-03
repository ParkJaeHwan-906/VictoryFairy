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

    /**
     * 이름에 주어진 문자열이 포함된 선수를 name 오름차순으로 조회한다({@code LIKE '%name%'}).
     *
     * <p>부분 일치(prefix 가 아니라 contains)를 쓰는 이유는 검색어가 이름 앞부분이라는 보장이 없어서다
     * ("도영" 으로 "김도영" 을 찾을 수 있어야 한다). 대소문자는 MySQL 기본 콜레이션(`_ci`)이 흡수하므로
     * {@code IgnoreCase} 를 붙이지 않는다 — 붙이면 양쪽에 {@code LOWER()} 가 끼어 SQL 만 지저분해진다.
     *
     * <p><b>선행 와일드카드라 name 인덱스를 타지 못한다.</b> 선수 테이블은 리그 전체를 합쳐도 수백 행
     * 규모라 풀스캔이 문제되지 않는다는 전제이며, 규모가 커지면 검색 전용 인덱스(또는 전문 검색)로
     * 옮겨야 한다.
     */
    List<Player> findAllByNameContainingOrderByNameAsc(String name);

    /**
     * 특정 구단 소속이면서 이름에 주어진 문자열이 포함된 선수를 name 오름차순으로 조회한다.
     *
     * <p>{@code findAllByTeam_IdOrderByNameAsc} 와 {@code findAllByNameContainingOrderByNameAsc} 를
     * AND 로 합친 형태다. 두 쿼리를 앱에서 교집합 내지 않고 조건 하나로 합치는 이유는, 구단 필터가
     * 걸린 상태에서도 결과 집합을 DB 가 한 번에 좁히고 정렬까지 끝내게 하기 위해서다.
     */
    List<Player> findAllByTeam_IdAndNameContainingOrderByNameAsc(Long teamId, String name);
}
