package com.skhynix.domain.team.repository;

import com.skhynix.domain.team.entity.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {

    // teams.name 콜레이션 순서라 영문 구단이 한글 구단보다 앞에 온다 (docs/requirements/user/team-list.md)
    List<Team> findAllByOrderByNameAsc();

    /**
     * KBO 구단 코드(HH·KT·LG…)로 조회 — 퀴즈 적재가 S3 후보의 {@code subject.teamCodes}를 FK 로
     * 해석할 때 쓴다. {@code teams.code} UNIQUE 가 {@code Optional} 반환을 뒷받침한다.
     */
    Optional<Team> findByCode(String code);
}
