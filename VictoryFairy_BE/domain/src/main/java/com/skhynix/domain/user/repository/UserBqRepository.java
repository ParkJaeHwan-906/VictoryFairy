package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserBq;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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

    /**
     * 그 구단을 <b>활성 응원 중</b>인 계정을 점수 내림차순·계정 id 오름차순으로 앞에서 {@code limit} 건.
     *
     * <p>모집단의 뿌리가 {@code users_bq} 가 아니라 {@code user_support_team} 인 이유: {@code users_bq} 에서
     * 시작하면 행이 없는 계정이 목록에서 빠진다(그 계정은 0 점으로 <b>포함</b>돼야 한다). 같은 이유로
     * {@code UserBq} 쪽이 left join 이고 점수는 {@code coalesce} 로 접는다.
     *
     * <p>⚠ {@code exit_at} 조건을 붙이지 말 것 — 탈퇴 계정을 모집단에 넣는 것이 계약이다
     * ({@code docs/requirements/user/team-bq-ranking.md} 결정 4). 하드 삭제 시 CASCADE 로 자연히 빠진다.
     *
     * <p>정렬 축이 둘인 것은 결정성 때문이다 — 점수만으로 정렬하면 동점자의 순서가 요청마다 바뀔 수 있고,
     * 상한에서 잘리는 계정이 달라진다. 순위 숫자를 여기서 매기지 않는 이유는 윈도 함수가 JPQL 에 없어서인데,
     * 이 목록은 1 위부터의 접두이므로 호출부가 "앞 항목과 점수가 같으면 같은 순위"로 매겨도 결과가 같다.
     */
    @Query("select ua.nickname as nickname, ua.profileImgUrl as profileImgUrl, "
            + "coalesce(ub.bqScore, 0L) as bqScore "
            + "from UserSupportTeam ust join ust.userAccount ua "
            + "left join UserBq ub on ub.userAccount.id = ua.id "
            + "where ust.team.id = :teamId and ust.oppose is null "
            + "order by coalesce(ub.bqScore, 0L) desc, ua.id asc")
    List<BqRankingEntryView> findTeamRanking(@Param("teamId") Long teamId, Limit limit);

    /**
     * 그 구단 모집단에서 주어진 점수보다 <b>엄격히 높은</b> 계정 수. {@code +1} 이 곧 그 점수의 순위다
     * (SQL {@code RANK()} 와 같은 1·1·3 방식 — 동점자는 세지 않으므로 동점 몇 명 중 하나여도 같은 순위).
     *
     * <p>점수를 인자로 받는 이유는 {@code /me} 가 이미 읽어 둔 값을 다시 읽지 않기 위해서다 — 계정 id 로 받아
     * 안에서 점수를 다시 조회하면 그 경로의 SELECT 가 1 회가 아니라 2 회 늘어난다.
     */
    @Query("select count(ust.id) from UserSupportTeam ust "
            + "left join UserBq ub on ub.userAccount.id = ust.userAccount.id "
            + "where ust.team.id = :teamId and ust.oppose is null "
            + "and coalesce(ub.bqScore, 0L) > :bqScore")
    long countHigherInTeam(@Param("teamId") Long teamId, @Param("bqScore") long bqScore);

    /**
     * 한 계정의 순위표 재료(닉네임·이미지 EP·점수)를 한 번에. 계정과 점수 행을 따로 읽으면 SELECT 가 2 회다.
     * {@code users_bq} 행이 없어도 계정이 있으면 0 점으로 돌아오고, 계정 자체가 없을 때만 비어 있다.
     */
    @Query("select ua.nickname as nickname, ua.profileImgUrl as profileImgUrl, "
            + "coalesce(ub.bqScore, 0L) as bqScore "
            + "from UserAccount ua left join UserBq ub on ub.userAccount.id = ua.id "
            + "where ua.id = :userAccountId")
    Optional<BqRankingEntryView> findRankingEntry(@Param("userAccountId") Long userAccountId);
}
