package com.skhynix.domain.user.repository;

import com.skhynix.domain.user.entity.UserBlock;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    /**
     * (blocker, blocked) 조합 단건 존재 확인 — 차단 생성 멱등 판정(USER-BLK-7)의 근거다.
     * DB UNIQUE({@code uk_user_blocks_blocker_blocked})가 최종 방어선이고, 이 조회는 경합이 없는
     * 정상 경로에서 불필요한 INSERT 시도(및 그로 인한 예외 처리)를 피하기 위한 사전 확인이다.
     */
    boolean existsByBlocker_IdAndBlocked_Id(Long blockerId, Long blockedId);

    /**
     * 특정 계정과 <b>양방향으로</b> 차단 관계에 있는 상대 계정 id 전체를 반환한다 — 그 계정이
     * 차단한 대상 + 그 계정을 차단한 주체를 합친 집합이다(USER-BLK-13). {@code UserBlock} 행 자체는
     * 단방향(blocker → blocked)으로만 저장되므로, "서로 안 보인다"는 상호 비노출 불변식은 저장이
     * 아니라 이 조회가 만든다.
     *
     * <p>랭킹(user)·채팅(quiz) 두 모듈이 "차단 관계자 전원 제외" 필터에 그대로 쓰는 공용
     * 진입점이다 — 반환된 id 집합을 제외 조건(NOT IN 등)에 바로 넣으면 된다.
     */
    @Query("select case when b.blocker.id = :accountId then b.blocked.id else b.blocker.id end "
            + "from UserBlock b where b.blocker.id = :accountId or b.blocked.id = :accountId")
    Set<Long> findRelatedAccountIds(@Param("accountId") Long accountId);

    /**
     * 특정 계정이 차단한 대상 목록을 <b>차단 시각순으로</b> 조회한다(차단 목록 조회 API,
     * USER-BLK-10). 응답에 대상 닉네임이 필요해 {@code blocked}(UserAccount)를 함께 로딩한다 —
     * {@code @EntityGraph} 없이 부르면 목록 순회 시 항목 수만큼 N+1이 난다
     * ({@code UserSupportTeamRepository.findWithTeamByUserAccount_IdAndOpposeIsNull}과 동일한 이유).
     */
    @EntityGraph(attributePaths = "blocked")
    List<UserBlock> findAllByBlocker_IdOrderByCreatedAtAsc(Long blockerId);
}
