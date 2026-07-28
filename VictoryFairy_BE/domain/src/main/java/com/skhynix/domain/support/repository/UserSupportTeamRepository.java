package com.skhynix.domain.support.repository;

import com.skhynix.domain.support.entity.UserSupportTeam;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSupportTeamRepository extends JpaRepository<UserSupportTeam, Long> {

    /**
     * 해당 계정이 <b>현재 응원 중인</b> 구단 행을 조회한다({@code oppose is null}).
     *
     * <p>{@code Optional} 인 이유는 "한 사용자는 구단을 1개만 응원한다"는 <b>서비스 정책</b> 때문이다.
     * 스키마에는 이 상한을 걸지 않았으므로(정책이 바뀌어도 마이그레이션이 필요 없게), 정책을 지키는 책임은
     * 쓰기 경로에 있다. 정책이 깨진 데이터가 있으면 이 메서드는 예외로 드러난다 — 조용히 첫 행을 고르지
     * 않는 것이 의도다.
     *
     * <p>연관 경로는 {@code UserAccount_Id} 로 끊어 Spring Data 가 {@code userAccount.id} 로만 해석하게
     * 한다({@code PlayerRepository.findAllByTeam_IdOrderByNameAsc} 와 같은 이유). 조건이 FK 컬럼
     * ({@code user_account_id}) 하나라 {@code users_account} 를 조인하지 않는다.
     */
    Optional<UserSupportTeam> findByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    /**
     * (계정, 구단) 행을 {@code oppose} 여부와 <b>무관하게</b> 조회한다.
     *
     * <p>응원 취소/재응원이 이력 행을 쌓지 않고 같은 행을 토글하는 설계라, 취소된 행까지 찾을 수 있어야
     * 재응원({@code support()})이 성립한다. 이 조회가 없으면 UNIQUE 제약에 걸려 재응원 자체가 불가능하다.
     * {@code (user_account_id, team_id)} UNIQUE 인덱스로 단건이 보장된다.
     */
    Optional<UserSupportTeam> findByUserAccount_IdAndTeam_Id(Long userAccountId, Long teamId);
}
