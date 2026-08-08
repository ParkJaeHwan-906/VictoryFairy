package com.skhynix.domain.support.repository;

import com.skhynix.domain.support.entity.UserSupportPlayer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSupportPlayerRepository extends JpaRepository<UserSupportPlayer, Long> {

    // 선수는 구단과 달리 복수 응원 허용이라 List 반환(활성 4명 상한은 SupportService가 강제한다).
    // 선수·구단을 안 읽는 호출자(취소 일괄 처리, 상한 검사) 전용이다 — 응답을 만들 거라면 아래
    // findAllActiveWithPlayerAndTeam 을 쓴다. 여기에 fetch join 을 얹지 말 것: 이 호출자들은
    // getPlayer().getId() 만 보므로(프록시 초기화 없음) 조인 2개가 순수 낭비가 된다.
    List<UserSupportPlayer> findAllByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    /**
     * 현재 응원 중인 행을 선수·소속 구단까지 한 번에 가져온다(응답 생성 전용).
     *
     * <p>{@code UserSupportPlayer.player} 와 {@code Player.team} 이 모두 LAZY 라, 이 fetch join 이
     * 없으면 응답 변환({@code PlayerResponse.from})이 행마다 선수를, 다시 구단을 깨워 N+1 이 된다.
     * 특히 {@code team} 은 "응원 선수는 응원 구단 소속"이라는 불변식 덕에 1차 캐시에 이미 올라와
     * 있는 경우가 많아 <b>평소엔 쿼리가 안 보이지만</b>, 그 불변식이 깨진 데이터(응원 구단 없이
     * 선수 행만 남은 계정)나 구단을 먼저 안 읽는 호출 경로에서는 그대로 추가 SELECT 가 된다.
     * 운 좋게 캐시에 맞는 것과 쿼리로 보장하는 것은 다르다 — <b>제거하지 말 것.</b>
     *
     * <p>둘 다 ToOne fetch join 이라 행이 늘지 않는다. {@code distinct} 는 불필요하며(중복 제거
     * 비용만 붙는다) 페이징 제약도 없다.
     *
     * <p>{@code order by p.name} 은 호출부가 아니라 DB 가 정렬하기 위한 것이고, 이 순서가 응답
     * 계약이다(선수명 오름차순). 파생 쿼리명 대신 {@code @Query} 인 이유는 정렬 기준이 연관의
     * 중첩 속성({@code player.name})이라, 이름에 담으면 메서드명이 길어질 뿐 아니라 fetch 조인과
     * 별개의 조인이 정렬용으로 하나 더 생길 수 있어서다. 여기서는 fetch 로 만든 별칭 {@code p} 를
     * 정렬이 그대로 재사용한다({@code GameLineupRepository} 와 같은 형태).
     *
     * <p>SELECT 2회 → 1회(응원 선수 있는 계정 기준, 정적 분석 — show-sql 미측정).
     */
    @Query("select usp from UserSupportPlayer usp "
            + "join fetch usp.player p "
            + "join fetch p.team "
            + "where usp.userAccount.id = :userAccountId and usp.oppose is null "
            + "order by p.name asc")
    List<UserSupportPlayer> findAllActiveWithPlayerAndTeam(@Param("userAccountId") Long userAccountId);

    // oppose 무관 조회 — 취소된 행도 찾아야 재응원(support())이 같은 행 재활성으로 성립한다
    Optional<UserSupportPlayer> findByUserAccount_IdAndPlayer_Id(Long userAccountId, Long playerId);
}
