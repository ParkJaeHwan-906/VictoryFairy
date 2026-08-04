package com.skhynix.domain.game.repository;

import com.skhynix.domain.game.entity.GameLineup;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GameLineupRepository extends JpaRepository<GameLineup, Long> {

    List<GameLineup> findByGameId(Long gameId);

    /** 선발 라인업(타순 1~9 + 선발투수)만 조회. */
    List<GameLineup> findByGameIdAndIsStarterTrue(Long gameId);

    // 아래 두 메서드는 파생 쿼리명 대신 @Query를 쓴다. 이유는 두 가지다.
    // ① 응답 DTO가 player.name·position.name을 읽는데 둘 다 LAZY라, prod(open-in-view:false)에서는
    //    LazyInitializationException이고 dev에서도 선발 20여 행에 대한 N+1이다. fetch join으로 못 박는다.
    //    position은 nullable(position_id NULL 허용)이라 반드시 left join — inner로 걸면 포지션 미상 선발이
    //    목록에서 통째로 사라진다. 반대로 team은 fetch 대상이 아니다: 소비처가 getTeam().getId()만 읽고
    //    이는 FK 값이라 프록시를 초기화하지 않는다(굳이 조인하면 쓰지 않는 teams 조인이 하나 더 붙는다).
    // ② 투수 정렬 키가 연관 컬럼(선수 이름)이라 파생 쿼리명으로는 OrderByPlayer_NameAsc까지 길어지고,
    //    그렇게 만들어도 위 fetch join은 @EntityGraph를 덧붙여야만 얻어진다.

    // 선발 투수: 타석에 서지 않는 선수라 bat_order가 NULL이다(포지션 'P'로 나누지 않는 이유는
    // Position 값이 py-collector가 원문을 그대로 적재하는 열린 집합이기 때문 — 미지 표기 하나에 분류가 무너진다).
    @Query("select gl from GameLineup gl "
            + "join fetch gl.player p "
            + "left join fetch gl.position "
            + "where gl.game.id = :gameId and gl.isStarter = true and gl.batOrder is null "
            + "order by p.name asc")
    List<GameLineup> findStarterPitchers(@Param("gameId") Long gameId);

    // 선발 타자: 타순 1~9. is not null 대신 범위를 명시해 적재 이상(0·10 등 규격 밖 타순)이
    // 타자 목록에 섞이지 않게 한다 — 투수 조회(bat_order is null)와 합쳐도 두 목록은 여전히 겹치지 않는다.
    @Query("select gl from GameLineup gl "
            + "join fetch gl.player "
            + "left join fetch gl.position "
            + "where gl.game.id = :gameId and gl.isStarter = true and gl.batOrder between 1 and 9 "
            + "order by gl.batOrder asc")
    List<GameLineup> findStarterBatters(@Param("gameId") Long gameId);
}
