package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserBq;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBqRepository extends JpaRepository<UserBq, Long> {

    /**
     * 해당 계정의 누적 점수 행을 조회한다. {@code Optional} 인 이유는 "모든 계정에 행이 있다"가 스키마
     * 제약이 아니라 쓰기 경로가 만드는 전제라서다 — 행이 없어도 만들지 않고 그대로 비워 돌려준다
     * (안전망, {@code docs/requirements/user/me-profile.md}).
     */
    Optional<UserBq> findByUserAccount_Id(Long userAccountId);

    /**
     * 적립용 조회 — 해당 계정의 누적 점수 행을 <b>행 단위 배타 락</b> 아래에서 읽는다
     * ({@code UserAccountRepository.findWithLockById} 와 같은 계열).
     *
     * <p>일반 {@code findByUserAccount_Id} 로 읽고 더하면 같은 계정의 동시 제출끼리 적립이 유실된다
     * (lost update). {@link UserBq#addBqScore(long)} 를 부르는 경로는 반드시 이쪽으로 읽는다.
     *
     * <p>⚠ <b>습관적으로 쓰지 말 것</b> — 조회 경로에 붙이면 읽기끼리 직렬화된다. 그리고 이 락은
     * <b>계정 행 락을 이미 쥔 뒤</b>에 잡는다(락 순서 고정 — 역순으로 잡는 경로가 생기면 데드락).
     *
     * <p>행이 없으면 비어 돌아온다 — 여기서 만들지 않는다. 없는 계정에 적립할 때 행을 만드는 것은
     * 적립 트랜잭션의 책임이고, 그 트랜잭션은 이미 계정 행 락으로 직렬화돼 있어 UNIQUE 충돌 창이
     * 없다({@code docs/requirements/quiz/quiz-point-bq-split.md} 의 결정 근거 Q1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ub from UserBq ub where ub.userAccount.id = :userAccountId")
    Optional<UserBq> findWithLockByUserAccountId(@Param("userAccountId") Long userAccountId);
}
