package com.skhynix.domain.support.repository;

import com.skhynix.domain.support.entity.UserSupportPlayer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSupportPlayerRepository extends JpaRepository<UserSupportPlayer, Long> {

    // 선수는 구단과 달리 복수 응원 허용·상한 없음이라 List 반환
    List<UserSupportPlayer> findAllByUserAccount_IdAndOpposeIsNull(Long userAccountId);

    // oppose 무관 조회 — 취소된 행도 찾아야 재응원(support())이 같은 행 재활성으로 성립한다
    Optional<UserSupportPlayer> findByUserAccount_IdAndPlayer_Id(Long userAccountId, Long playerId);
}
