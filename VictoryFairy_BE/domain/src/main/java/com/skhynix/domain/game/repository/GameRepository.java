package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.Game;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

    // games.game_date는 datetime(6)이라 자정인 값이 없다 — 등치 비교는 항상 0건, DATE()는 인덱스를 못 타고,
    // Between은 상한 포함이라 경계가 항상 어긋난다. 날짜 조회는 반드시 반개구간 [00:00, +1일 00:00)으로 한다.
    // @EntityGraph 목록은 GameResponse가 읽는 연관과 1:1 유지 — 안 그러면 N+1 부활 + prod(open-in-view:false)에서 LazyInitializationException.
    // stadium만 optional=true라 left join으로 나간다(구장 미정 경기도 목록에서 빠지면 안 됨).
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "stadium", "gameStatus"})
    List<Game> findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
            LocalDateTime startInclusive, LocalDateTime endExclusive);
}
