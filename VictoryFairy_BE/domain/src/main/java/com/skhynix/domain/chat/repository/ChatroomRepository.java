package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.user.entity.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatroomRepository extends JpaRepository<Chatroom, Long> {

    // 채팅방은 구단 단위 폐쇄 공간 — 목록 조회는 요청자의 응원 구단 방만 본다
    List<Chatroom> findAllByTeam_IdAndDeletedAtIsNull(Long teamId);

    // 삭제됐거나 없는 방은 빈 결과 — 조회·구독·전송·히스토리 경로에서 404 판정 기준
    Optional<Chatroom> findByUidAndDeletedAtIsNull(String uid);

    /**
     * 방 소유자를 통째로 다른 계정으로 넘긴다. <b>호출자는 user 앱의 만료 데이터 정리 하나뿐이며</b>
     * (chat 서빙은 quiz 앱이 한다) 넘겨받는 쪽은 언제나 {@code (알수없음)} 더미 계정이다
     * ({@code docs/requirements/user/expired-data-cleanup.md}).
     *
     * <p>이관이 필요한 이유는 보존 정책이기 이전에 <b>스키마</b>다. {@code chatrooms.owner_account_id}
     * 는 NOT NULL + FK NO ACTION 이라 비울 수도, 계정을 먼저 지울 수도 없다 — 이관하지 않으면
     * {@code users} 삭제 자체가 FK 위반으로 실패한다. 방은 여러 사람이 쓰는 공용 자원이라 함께
     * 지우는 선택지도 없다.
     *
     * <p>{@code deleted_at} 으로 거르지 않는다. 소프트 삭제된 방도 행이 남아 FK 를 붙들고 있으므로
     * 하나라도 남기면 삭제가 막힌다.
     *
     * <p>벌크 UPDATE 라 {@code @UpdateTimestamp}({@code updated_at})가 갱신되지 않는다 — 소유자 이관은
     * 방 내용의 변경이 아니므로 의도된 동작이다.
     *
     * @return 이관된 방 수
     */
    @Modifying
    @Query("update Chatroom c set c.owner = :newOwner where c.owner.id = :previousOwnerId")
    int reassignOwner(@Param("previousOwnerId") Long previousOwnerId,
            @Param("newOwner") UserAccount newOwner);
}
