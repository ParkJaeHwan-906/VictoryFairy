package com.skhynix.domain.chat.repository;

import com.skhynix.domain.chat.entity.Chat;
import com.skhynix.domain.chat.entity.Chatroom;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
