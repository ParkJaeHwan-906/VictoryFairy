package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.entity.UserRefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    Optional<UserRefreshToken> findByRefreshToken(String refreshToken);

    void deleteByUserAccount(UserAccount userAccount);

    /**
     * 해당 account의 아직 유효한(expiredAt > now) refresh token을 모두 즉시 만료시킨다.
     * 유저당 유효 토큰을 1개로 유지하기 위해 새 토큰 발급 직전에 호출한다.
     *
     * @return 만료 처리된 행 수
     */
    @Modifying
    @Query("update UserRefreshToken t set t.expiredAt = :now "
            + "where t.userAccount = :userAccount and t.expiredAt > :now")
    int expireValidTokens(@Param("userAccount") UserAccount userAccount, @Param("now") LocalDateTime now);
}
