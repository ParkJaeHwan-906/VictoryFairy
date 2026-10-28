package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityCommentRepository extends JpaRepository<CommunityComment, Long> {

    // 소속 글의 삭제·블라인드 판정과 작성자 비교가 뒤따르므로 둘을 함께 싣는다. parent 는 id 만 쓴다.
    @EntityGraph(attributePaths = {"post", "userAccount"})
    Optional<CommunityComment> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 최상위 댓글 한 페이지 — 보이는 댓글과 <b>자리 표식</b>(삭제·블라인드됐지만 보이는 답글이 하나 이상
     * 남은 댓글)을 작성순으로 싣는다. 자리 표식 여부를 저장하지 않으므로 "보이는 답글이 있는가"를
     * 여기서 exists 로 판정한다 — 마지막 답글이 사라지는 순간 다음 조회부터 자리 표식도 사라진다.
     *
     * <p>본문·count 의 WHERE 는 항상 같게 유지할 것 — total 과 페이지 내용이 어긋나면 안 된다.
     */
    @Query(value = "select c from CommunityComment c join fetch c.userAccount "
            + "where c.post.id = :postId and c.parent is null and ("
            + "(c.deletedAt is null and c.blinded = false) or exists ("
            + "select r.id from CommunityComment r where r.parent = c "
            + "and r.deletedAt is null and r.blinded = false)) "
            + "order by c.id asc",
            countQuery = "select count(c) from CommunityComment c "
                    + "where c.post.id = :postId and c.parent is null and ("
                    + "(c.deletedAt is null and c.blinded = false) or exists ("
                    + "select r.id from CommunityComment r where r.parent = c "
                    + "and r.deletedAt is null and r.blinded = false))")
    Page<CommunityComment> findTopLevelPage(@Param("postId") Long postId, Pageable pageable);

    // 한 페이지의 답글을 IN 한 방으로 — 항목마다 부르면 N+1. 삭제·블라인드 답글은 여기서 거른다
    // (답글에는 자리 표식이 없다).
    @Query("select r from CommunityComment r join fetch r.userAccount "
            + "where r.parent.id in :parentIds and r.deletedAt is null and r.blinded = false "
            + "order by r.id asc")
    List<CommunityComment> findVisibleRepliesByParentIds(@Param("parentIds") Collection<Long> parentIds);

    /** 락 요구는 {@code CommunityPostRepository.findWithLockByIdAndDeletedAtIsNull} 과 같다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityComment c where c.id = :id and c.deletedAt is null")
    Optional<CommunityComment> findWithLockByIdAndDeletedAtIsNull(@Param("id") Long id);

    /** {@code CommunityPostRepository.reassignAuthor} 와 짝 — 댓글·답글 모두 한 번에 넘긴다. */
    @Modifying
    @Query("update CommunityComment c set c.userAccount = :newOwner "
            + "where c.userAccount.id = :previousOwnerId")
    int reassignAuthor(@Param("previousOwnerId") Long previousOwnerId,
            @Param("newOwner") UserAccount newOwner);
}
