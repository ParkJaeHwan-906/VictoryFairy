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

    /**
     * 이미 만료된 토큰 행을 지운다 — 만료 데이터 정리가 부르는 유일한 호출자다. 유예 기간은 없다
     * ({@code expiredAt <= 기준 시각}이면 곧바로 대상): 만료된 토큰은 재발급에 쓸 수 없어 남겨 둘
     * 이유가 없고, 로그아웃·재발급이 만료 처리만 하고 지우지는 않아 방치하면 계속 쌓이기만 한다.
     *
     * <p>경계가 {@code <=} 인 것은 {@link #expireValidTokens} 가 "즉시 만료"를 {@code expiredAt = now}
     * 로 표현하기 때문이다 — {@code <} 로 두면 방금 로그아웃·탈퇴로 만료시킨 행이 그 회차에서만
     * 살아남아 규칙이 시각에 따라 흔들린다.
     *
     * <p>계정 하드 삭제로 CASCADE 되어 함께 사라지는 행은 이 건수에 잡히지 않는다 — 정리 순서가
     * "계정 삭제 → 토큰 삭제"라 그 행들은 여기 도달하기 전에 이미 없다.
     *
     * @return 삭제된 행 수
     */
    @Modifying
    @Query("delete from UserRefreshToken t where t.expiredAt <= :baseTime")
    int deleteExpiredTokens(@Param("baseTime") LocalDateTime baseTime);
}
