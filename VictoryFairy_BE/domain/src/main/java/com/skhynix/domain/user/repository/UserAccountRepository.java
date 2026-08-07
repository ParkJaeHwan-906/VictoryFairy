package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * 계정 행을 비관적 쓰기 락으로 잡아 <b>같은 계정의 응원 상태 변경을 직렬화</b>한다.
     *
     * <p>응원 선수 상한 판정은 "활성 응원 선수를 읽고 → 개수를 판정하고 → 저장"이라 그 사이가 열려 있으면
     * 두 요청이 각자 상한을 통과해 최종 개수가 상한을 넘는다. UNIQUE {@code (user_account_id, player_id)} 는
     * 같은 선수의 중복만 막고 개수는 막지 못한다.
     *
     * <p>잠그는 대상이 응원 행이 아니라 <b>계정 행</b>인 이유: 지키려는 불변식은 <b>비어 있을 수 있는 집합</b>
     * 에 대한 진술이라, 활성 응원 선수가 0명인 계정에는 잠글 원소가 없다. 계정 행은 행 수가 0이 될 수 없는
     * 유일한 앵커다. 응원 행 쪽 잠금이 원리상 불가능하다는 뜻은 아니다 — REPEATABLE READ 의 넥스트키 락은
     * 갭까지 잠가 팬텀 INSERT 를 막을 수 있다. 다만 갭 락은 READ COMMITTED 에서 사라지고 어느 인덱스를
     * 타느냐로 범위가 달라져, <b>격리 수준·인덱스 선택에 의존하지 않는</b> PK 단일 행 잠금을 택한 것이다.
     *
     * <p>이 락이 잠그는 것은 {@code users_account} 한 행뿐이며 FK 로 딸린 자식 행은 잠기지 않는다. 다만
     * InnoDB 는 자식 행 INSERT 시 FK 검사로 부모 행에 공유 락을 잡으므로, 이 락을 쥔 동안 <b>그 계정을
     * 참조하는 자식 테이블 쓰기 전반</b>(응원 행뿐 아니라 refresh 토큰 발급 등)이 대기한다. 트랜잭션이
     * 단문 몇 개라 ms 단위지만, 여기서 오래 걸리는 작업을 하면 그 대기가 그대로 번진다.
     *
     * <p>⚠ 지우거나 일반 {@code findById} 로 갈아끼우면 상한이 그대로 뚫린다. 반대로 <b>습관적으로 쓰지도 말
     * 것</b> — 조회 경로에 붙이면 서로를 막아 읽기끼리 직렬화된다. 계정 단위 상태를 실제로 변경하는 트랜잭션
     * 에서만, 그리고 항상 그 트랜잭션의 가장 앞에서(락 순서 고정) 호출한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ua from UserAccount ua where ua.id = :id")
    Optional<UserAccount> findWithLockById(@Param("id") Long id);
}
