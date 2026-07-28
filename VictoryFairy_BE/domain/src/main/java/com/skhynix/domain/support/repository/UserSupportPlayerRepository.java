package com.skhynix.domain.support.repository;

import com.skhynix.domain.support.entity.UserSupportPlayer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSupportPlayerRepository extends JpaRepository<UserSupportPlayer, Long> {

    /**
     * 해당 계정이 <b>현재 응원 중인</b> 선수 행 전체를 조회한다({@code oppose is null}).
     *
     * <p>구단과 달리 선수는 <b>복수 응원을 허용하고 상한이 없다</b>는 정책이라 {@code List} 를 반환한다.
     * 연관 경로는 {@code UserAccount_Id} 로 끊어 {@code userAccount.id} 로만 해석되게 하며, 조건이 FK
     * 컬럼 하나라 {@code users_account} 를 조인하지 않는다.
     */
    List<UserSupportPlayer> findAllByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    /**
     * (계정, 선수) 행을 {@code oppose} 여부와 <b>무관하게</b> 조회한다.
     *
     * <p>취소된 행까지 찾을 수 있어야 재응원({@code support()})이 같은 행의 재활성으로 성립한다. 이 조회가
     * 없으면 UNIQUE 제약에 걸려 재응원이 불가능하다. {@code (user_account_id, player_id)} UNIQUE
     * 인덱스로 단건이 보장된다.
     */
    Optional<UserSupportPlayer> findByUserAccount_IdAndPlayer_Id(Long userAccountId, Long playerId);
}
