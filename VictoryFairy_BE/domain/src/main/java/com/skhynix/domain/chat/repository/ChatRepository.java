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

    /**
     * 방의 히스토리 조회. {@code blind=true}이거나 소프트 삭제된 메시지는 제외하고, 최신순
     * ({@code createdAt} desc)으로 페이징한다. WHERE 절에서 blind/삭제를 걸러 유효 메시지만으로
     * 페이지가 채워진다(빈 슬롯이 새지 않는다).
     *
     * <p>{@code userAccount}를 fetch join으로 함께 로딩한다. 소비처({@code MessageResponse.from})가 각
     * {@code Chat}의 {@code userAccount.nickname}(LAZY {@code @ManyToOne})에 접근하므로, fetch join이
     * 없으면 페이지 행 수만큼 추가 SELECT가 나가 페이지당 N+1이 된다(30건 기준 1 + 30). fetch join으로
     * 히스토리 SELECT 1회에 nickname까지 실어 온다. — 이 join fetch를 제거하지 말 것.
     *
     * <p>{@code userAccount}는 컬렉션이 아닌 to-one 연관이라 fetch join + {@code Pageable} 조합이
     * 안전하다(Hibernate가 LIMIT을 SQL에 적용, 전체 메모리 페이징 경고 {@code HHH90003004}·카테시안 곱
     * 없음). 이 위험은 컬렉션 fetch join에만 해당한다.
     *
     * <p>{@code countQuery}를 명시한 이유: 페이지 total 계산에는 nickname이 필요 없으므로 count에서는
     * {@code users_account} 조인을 빼 불필요한 조인을 없앤다. 명시하지 않으면 Spring Data가 본문 쿼리의
     * fetch join까지 포함한 count를 파생시켜 조인이 딸려 들어간다. {@code order by}도 count에선 무의미해
     * 제거했다. WHERE 필터(blind=false·deletedAt is null)는 본문·count 양쪽을 동일하게 유지해 total과
     * 실제 페이지 내용의 필터가 어긋나지 않게 했다.
     */
    @Query(value = "select c from Chat c join fetch c.userAccount "
            + "where c.chatroom = :chatroom and c.blind = false and c.deletedAt is null "
            + "order by c.createdAt desc",
            countQuery = "select count(c) from Chat c "
                    + "where c.chatroom = :chatroom and c.blind = false and c.deletedAt is null")
    Page<Chat> findByChatroomAndBlindFalseAndDeletedAtIsNullOrderByCreatedAtDesc(
            @Param("chatroom") Chatroom chatroom, Pageable pageable);

    /**
     * 신고 대상 메시지를 room-스코프로 조회한다. 경로의 {@code roomUid}가 가리키는 방에 속한 메시지만
     * 찾아, 다른 방의 PK를 지목하는 접근을 걸러낸다.
     */
    Optional<Chat> findByIdAndChatroom(Long id, Chatroom chatroom);
}
