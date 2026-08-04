package com.skhynix.domain.team.repository;

import com.skhynix.domain.team.entity.Team;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // teams.name 콜레이션 순서라 영문 구단이 한글 구단보다 앞에 온다 (docs/requirements/user/team-list.md)
    List<Team> findAllByOrderByNameAsc();
}
