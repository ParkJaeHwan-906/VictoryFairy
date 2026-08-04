package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    // 탈퇴 계정을 "못 찾음"으로 흡수 — login이 미가입 이메일과 동일한 응답(INVALID_CREDENTIALS)이 되도록 한다.
    Optional<UserAccount> findByUser_EmailAndExitAtIsNull(String email);

    // 파생 쿼리명으로는 id 프로젝션을 표현 못 해 @Query로 명시. exit_at 조건 때문에 uid 인덱스만으로
    // 커버링되지 않지만, access 토큰이 stateless(3h)라 이 조회가 탈퇴 즉시 차단의 유일한 지점이라 감수한다
    // (트레이드오프 근거: docs/requirements/user/withdraw.md "결정 근거 2").
    @Query("select ua.id from UserAccount ua where ua.uid = :uid and ua.exitAt is null")
    Optional<Long> findActiveIdByUid(@Param("uid") String uid);

    boolean existsByNickname(String nickname);
}
