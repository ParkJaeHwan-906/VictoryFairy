package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserBq;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBqRepository extends JpaRepository<UserBq, Long> {

    /**
     * 해당 계정의 누적 점수 행을 조회한다. {@code Optional} 인 이유는 "모든 계정에 행이 있다"가 스키마
     * 제약이 아니라 쓰기 경로가 만드는 전제라서다 — 행이 없어도 만들지 않고 그대로 비워 돌려준다
     * (안전망, {@code docs/requirements/user/me-profile.md}).
     */
    Optional<UserBq> findByUserAccount_Id(Long userAccountId);
}
