package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import com.skhynix.domain.user.entity.UserAccount;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    // userAccount를 fetch join — 소비처가 각 메시지의 nickname(LAZY)을 읽으므로 없으면 페이지당 N+1이 된다.
    // to-one 연관이라 fetch join + Pageable 조합이 안전하다(컬렉션 fetch join과 달리 메모리 페이징 문제 없음).
    // countQuery를 별도 명시: count에는 nickname이 불필요해 조인을 뺐다(안 하면 fetch join까지 딸려 들어감).
    // 본문·count의 WHERE 필터(blind=false·deletedAt is null)는 항상 동일하게 유지 — total과 페이지 내용이 어긋나면 안 됨.
    @Query(value = "select c from Chat c join fetch c.userAccount "
            + "where c.chatroom = :chatroom and c.blind = false and c.deletedAt is null "
            + "order by c.createdAt desc",
            countQuery = "select count(c) from Chat c "
                    + "where c.chatroom = :chatroom and c.blind = false and c.deletedAt is null")
    Page<Chat> findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
            @Param("chatroom") Chatroom chatroom, Pageable pageable);

    // room-스코프 조회 — 다른 방 메시지 PK를 지목하는 접근을 차단(신고 대상 조회용)
    Optional<Chat> findByIdAndChatroom(Long id, Chatroom chatroom);

    /**
     * 남긴 메시지의 작성자를 통째로 다른 계정으로 넘긴다. {@link ChatroomRepository#reassignOwner}와
     * 짝이며, 호출자는 user 앱의 만료 데이터 정리 하나뿐이다
     * ({@code docs/requirements/user/expired-data-cleanup.md}).
     *
     * <p>메시지는 <b>지우지도, 비우지도</b> 않는다. 지우면 남은 사람의 대화창에서 상대 발언만 사라져
     * 맥락이 끊기고, 소유자를 NULL 로 비우면 히스토리 변환({@code MessageResponse.from()})이
     * {@code getUserAccount().getNickname()} 을 역참조하는 순간 NPE 다. 그래서 닉네임을 읽을 수 있는
     * 실제 계정 행 — {@code (알수없음)} 더미 계정 — 으로 넘긴다.
     *
     * <p>{@code blind}·{@code deleted_at} 으로 거르지 않는다. 계정 FK 는 CASCADE 라 남은 행이 있으면
     * 계정 삭제와 함께 그 메시지가 사라진다.
     *
     * @return 이관된 메시지 수
     */
    @Modifying
    @Query("update Chat c set c.userAccount = :newOwner where c.userAccount.id = :previousOwnerId")
    int reassignSender(@Param("previousOwnerId") Long previousOwnerId,
            @Param("newOwner") UserAccount newOwner);
}
