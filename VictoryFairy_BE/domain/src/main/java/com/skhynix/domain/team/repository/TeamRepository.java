package com.skhynix.domain.team.repository;

import com.skhynix.domain.team.entity.Team;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

    /**
     * 전체 구단을 {@code name} 오름차순으로 조회한다. 정렬은 DB(ORDER BY) 가 단독으로 수행하며
     * 애플리케이션에서 재정렬하지 않는다 — 정렬 기준이 두 곳으로 갈리는 것을 막기 위한 결정이다.
     *
     * <p>결과 순서는 로케일 정렬이 아니라 {@code teams.name} 콜레이션 순서다(영문 구단이 한글 구단보다
     * 앞에 온다). 근거·기대 순서는 {@code docs/requirements/user/team-list.md} 참고.
     */
    List<Team> findAllByOrderByNameAsc();
}
