package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    // 탈퇴 계정을 "못 찾음"으로 흡수 — login이 미가입 이메일과 동일한 응답(INVALID_CREDENTIALS)이 되도록 한다.
    Optional<UserAccount> findByUser_EmailAndExitAtIsNull(String email);

    /**
     * 요청 인증용 계정 조회 — uid→내부 PK 해석과 토큰 무효화 기준 시각을 <b>한 번의 조회</b>로 함께
     * 가져온다. {@code JwtAuthenticationFilter}가 요청마다 부르는 유일한 운영 호출자다.
     *
     * <p>파생 쿼리명으로는 프로젝션을 표현하지 못해 {@code @Query}로 명시한다. {@code exit_at} 조건
     * 때문에 uid 인덱스만으로 커버링되지 않지만, access 토큰이 stateless(3h)라 이 조회가 탈퇴 즉시
     * 차단의 유일한 지점이라 감수한다(근거: {@code docs/requirements/user/withdraw.md} "결정 근거 2").
     *
     * <p>⚠ 기준 시각을 <b>여기서 함께 싣는 것</b>이 계약이다. 별도 조회로 빼면 요청당 SELECT 가 늘어
     * "무효화 검사는 조회를 늘리지 않는다"(USER-ATI-13)가 깨진다. 반대로 엔티티 전체를 반환하도록
     * 바꾸는 것도 안 된다 — 요청마다 쓰지도 않는 컬럼을 전부 읽게 된다.
     */
    @Query("""
            select new com.skhynix.domain.user.repository.ActiveAccountView(
                       ua.id, ua.passwordChangedEpochSecond)
            from UserAccount ua
            where ua.uid = :uid and ua.exitAt is null
            """)
    Optional<ActiveAccountView> findActiveAuthByUid(@Param("uid") String uid);

    /**
     * uid 로 계정 1행을 있는 그대로 가져온다 — 탈퇴 여부를 <b>거르지 않는다</b>는 점에서
     * {@link #findActiveAuthByUid(String)} 와 다르다.
     *
     * <p>운영 호출자는 예약 계정({@code (알수없음)} 더미 계정)의 부트스트랩과 조회뿐이다. 그 계정은
     * 요청 인증에 쓰이지 않으므로 활성 조건이 필요 없고, 반대로 <b>엔티티가 필요하다</b> — 채팅방·채팅
     * 소유자를 이 계정으로 바꾸는 벌크 UPDATE 의 파라미터로 들어간다.
     *
     * <p>인증 경로에서 이 메서드를 쓰지 말 것. 탈퇴 계정이 그대로 나와 차단 지점이 뚫린다.
     */
    Optional<UserAccount> findByUid(String uid);

    /**
     * 하드 삭제 대상 — 탈퇴({@code exit_at IS NOT NULL}) 후 보존 기간이 지난 계정.
     *
     * <p>경계는 {@code exit_at <= :threshold} 이며, 호출자가 넘기는 {@code threshold} 는
     * "기준 시각 - 보존 기간"이다(= {@code exit_at + 보존기간 <= 기준 시각}). <b>경과 순간을 포함</b>하는
     * {@code <=} 가 계약이라 {@code <} 로 바꾸지 말 것.
     *
     * <p>{@code excludedUid} 는 예약 계정을 대상에서 빼기 위한 안전장치다. 그 계정은 {@code exit_at} 이
     * NULL 이라 위 조건만으로도 자연히 빠지지만, 누군가 실수로 예약 계정을 탈퇴시키면 그 계정이 보관 중인
     * <b>남의 채팅방·메시지가 통째로 사라진다</b> — 조건 하나로 그 사고를 원천 차단한다.
     *
     * <p>{@code ua.user.id} 는 to-one 의 식별자라 {@code users} 조인 없이 FK 컬럼으로 풀린다.
     * 정렬을 두지 않는 이유: 처리 순서가 결과에 영향을 주지 않고(계정 1건 = 트랜잭션 1개) 상한도 없다.
     */
    @Query("""
            select new com.skhynix.domain.user.repository.ExpiredAccountView(
                       ua.id, ua.uid, ua.user.id)
            from UserAccount ua
            where ua.exitAt is not null and ua.exitAt <= :threshold and ua.uid <> :excludedUid
            """)
    List<ExpiredAccountView> findExpiredAccounts(@Param("threshold") LocalDateTime threshold,
            @Param("excludedUid") String excludedUid);

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
