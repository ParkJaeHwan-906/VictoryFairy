package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.Game;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameRepository extends JpaRepository<Game, Long> {

    /**
     * 반개구간 {@code [startInclusive, endExclusive)} 에 걸리는 경기를 {@code gameDate} 오름차순으로 조회한다.
     * 하루치 목록은 호출부가 {@code date.atStartOfDay()} 와 {@code date.plusDays(1).atStartOfDay()} 를 넘긴다.
     *
     * <p><b>날짜 등치 비교를 쓰지 않는 이유</b>: {@code games.game_date} 는 {@code datetime(6)} 이고 실제 값이
     * 14:00·18:30 처럼 시각을 포함해 자정인 행이 없다. {@code game_date = :date} 는 항상 0건이다.
     *
     * <p><b>{@code DATE(game_date) = :date} 도 쓰지 않는다</b>: 컬럼을 함수로 감싸면 {@code game_date} 인덱스를
     * 탈 수 없다. 범위 조건으로 두면 인덱스 레인지 스캔이 그대로 살아 있고 정렬까지 인덱스가 만족시킨다.
     *
     * <p><b>{@code Between} 도 쓰지 않는다</b>: {@code Between} 은 상한 포함({@code <=})이라 {@code datetime(6)}
     * 에서 상한을 어떻게 잡아도 경계가 어긋난다(23:59:59 는 23:59:59.500000 을 놓치고, 다음날 자정을 상한으로
     * 주면 자정 경기가 두 날짜에 중복된다). 반개구간이면 이 문제가 구조적으로 사라진다.
     *
     * <p>{@code @EntityGraph} 로 {@code homeTeam}·{@code awayTeam}·{@code stadium}·{@code gameStatus} 를 함께
     * 가져와 응답 변환 시의 N+1 을 막는다. 여기 적힌 연관 목록은 {@code GameResponse} 가 실제로 읽는 연관과
     * 1:1 로 대응해야 한다 — 응답에 필드를 더하면서 이 목록을 안 고치면 경기 수만큼 추가 쿼리가 나가고,
     * 운영({@code open-in-view: false})에서는 {@code LazyInitializationException} 까지 난다.
     *
     * <p>{@code stadium} 만 선택적 연관({@code optional = true})이라 이 연관은 left join 으로 나간다 —
     * 구장이 미정인 경기도 목록에서 빠지지 않아야 하기 때문이다.
     */
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "stadium", "gameStatus"})
    List<Game> findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
            LocalDateTime startInclusive, LocalDateTime endExclusive);
}
