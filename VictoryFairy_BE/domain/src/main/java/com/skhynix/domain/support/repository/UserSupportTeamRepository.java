package com.skhynix.domain.support.repository;

import com.skhynix.domain.support.entity.UserSupportTeam;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSupportTeamRepository extends JpaRepository<UserSupportTeam, Long> {

    /**
     * 해당 계정이 <b>현재 응원 중인</b> 구단 행을 조회한다({@code oppose is null}).
     *
     * <p>구단을 <b>id 로만</b> 쓰는 호출자용이다. 구단명이 필요하면
     * {@link #findWithTeamByUserAccount_IdAndOpposeIsNull} 을 쓴다.
     */
    Optional<UserSupportTeam> findByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    /**
     * 위 메서드와 조건은 같고 구단까지 함께 가져온다. 응답에 구단명이 필요한 호출자용이다.
     *
     * <p>이 {@code @EntityGraph} 를 빼거나 조건이 같은 {@link #findByUserAccount_IdAndOpposeIsNull} 로
     * 되돌리면 SELECT 가 4→5회가 되어 USER-ME-22(SELECT ≤ 4, {@code docs/requirements/user/me-profile.md})
     * 가 깨진다.
     */
    @EntityGraph(attributePaths = "team")
    Optional<UserSupportTeam> findWithTeamByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    /**
     * (계정, 구단) 행을 {@code oppose} 여부와 무관하게 조회한다. 재응원({@code support()})용 — 없으면
     * UNIQUE 제약에 걸려 재응원이 불가능하다.
     */
    Optional<UserSupportTeam> findByUserAccount_IdAndTeam_Id(Long userAccountId, Long teamId);
}
