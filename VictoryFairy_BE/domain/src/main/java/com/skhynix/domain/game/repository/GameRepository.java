package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.Game;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameRepository extends JpaRepository<Game, Long> {

    // games.game_date는 datetime(6)이라 자정인 값이 없다 — 등치 비교는 항상 0건, DATE()는 인덱스를 못 타고,
    // Between은 상한 포함이라 경계가 항상 어긋난다. 날짜 조회는 반드시 반개구간 [00:00, +1일 00:00)으로 한다.
    // @EntityGraph 목록은 GameResponse가 읽는 연관과 1:1 유지 — 안 그러면 N+1 부활 + prod(open-in-view:false)에서 LazyInitializationException.
    // stadium만 optional=true라 left join으로 나간다(구장 미정 경기도 목록에서 빠지면 안 됨).
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "stadium", "gameStatus"})
    List<Game> findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
            LocalDateTime startInclusive, LocalDateTime endExclusive);

    // 위 조회에 "지정 구단이 홈이거나 원정" 조건을 더한 변형 — user 앱의 GET /games/support 전용.
    // 파생 쿼리명을 쓰지 않는 이유: OR 를 이름으로 표현하면 날짜 두 조건까지 OR 우선순위에 휘말려
    // 괄호를 못 씌운다(날짜 범위 밖 경기가 섞인다). 조건 구조가 이름으로 안전하게 표현되지 않는 경우다.
    // homeTeam.id/awayTeam.id 는 games 행의 FK 컬럼이라 이 비교만으로는 조인이 생기지 않는다
    // (실제 teams 조인은 아래 @EntityGraph 가 만든다).
    // @EntityGraph 목록은 위 조회와 동일하게 유지 — GameResponse 가 읽는 연관과 1:1 이며,
    // 빠지면 N+1 부활 + prod(open-in-view:false)에서 LazyInitializationException.
    @EntityGraph(attributePaths = {"homeTeam", "awayTeam", "stadium", "gameStatus"})
    @Query("select g from Game g "
            + "where g.gameDate >= :startInclusive and g.gameDate < :endExclusive "
            + "and (g.homeTeam.id = :teamId or g.awayTeam.id = :teamId) "
            + "order by g.gameDate asc")
    List<Game> findAllByTeamAndGameDateRange(@Param("teamId") Long teamId,
            @Param("startInclusive") LocalDateTime startInclusive,
            @Param("endExclusive") LocalDateTime endExclusive);

    // naver_game_id는 py-collector 소유 자연키이자 외부에 노출된 유일한 경기 식별자다(GameResponse.gameId).
    // 위 목록 조회와 달리 @EntityGraph를 붙이지 않는다 — 라인업 조회는 이 결과에서 내부 PK만 꺼내 쓰고
    // 구단·구장·상태를 읽지 않으므로, 연관을 끌고 오면 쓰지도 않는 조인 4개가 매 요청 붙는다.
    // 없는 값이면 빈 Optional → 호출부가 GAME_NOT_FOUND(404)로 바꾼다(빈 문자열도 같은 경로로 흡수).
    Optional<Game> findByNaverGameId(String naverGameId);

    // 위와 같은 해석 조회에 상태(game_statuses)만 함께 실어 오는 변형 — quiz 앱의 GET /today 가
    // "지목된 경기가 오늘·내 응원 구단·IN_PROGRESS 인가" 4종을 판정하는 데 쓴다.
    // ⚠ 그 판정은 요청당 games 조회 1회가 계약이다. 상태는 이름(name) 문자열로 봐야 하는데
    //   (game_statuses 의 id 는 py-collector 가 만난 순서로 부여돼 환경마다 다르다) name 은 FK 값이
    //   아니라 상태 행의 컬럼이라, 조인 없이 LAZY 로 두면 판정 순간 SELECT 가 한 번 더 나간다.
    // homeTeam/awayTeam 을 attributePaths 에 넣지 않은 것은 의도다 — 응원 구단 판정이 id 비교뿐이고
    //   그 id 는 games 행의 FK 컬럼이라 프록시 초기화 없이 읽힌다(조인을 더해도 얻는 게 없다).
    @EntityGraph(attributePaths = {"gameStatus"})
    Optional<Game> findWithStatusByNaverGameId(String naverGameId);
}
