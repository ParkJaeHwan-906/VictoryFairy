package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.user.entity.UserAccount;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    // 목록 변환이 category.name·userAccount.nickname(둘 다 LAZY)을 읽으므로 둘을 함께 싣는다 — 없으면
    // 페이지당 N+1. to-one 두 개라 Pageable 과 안전하게 조합된다(컬렉션 fetch 가 아니다).
    // 정렬은 호출자의 Pageable(Sort) 이 정한다(latest=id desc / likes=like_count desc,id desc / views).
    @EntityGraph(attributePaths = {"category", "userAccount"})
    Page<CommunityPost> findAllByDeletedAtIsNullAndBlindedFalse(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "userAccount"})
    Page<CommunityPost> findAllByCategory_IdAndDeletedAtIsNullAndBlindedFalse(Long categoryId,
            Pageable pageable);

    // 내 글 — 블라인드를 거르지 않는다(마이페이지에서 blinded:true 로 본인에게 알린다)
    @EntityGraph(attributePaths = {"category", "userAccount"})
    Page<CommunityPost> findAllByUserAccount_IdAndDeletedAtIsNullOrderByIdDesc(Long userAccountId,
            Pageable pageable);

    /**
     * 인기 게시글 — 점수({@code likeCount×10 + viewCount}) 내림차순, 동점은 최신(id 내림차순).
     * 점수는 저장하지 않고 정렬식으로만 계산한다(가중치가 바뀌면 이 식 하나만 바뀐다).
     * {@code since} 는 호출자의 {@code Clock} 에서 나온 "지금 − 7일"이다.
     */
    @Query("select p from CommunityPost p join fetch p.category join fetch p.userAccount "
            + "where p.deletedAt is null and p.blinded = false and p.createdAt >= :since "
            + "order by (p.likeCount * 10 + p.viewCount) desc, p.id desc")
    List<CommunityPost> findPopular(@Param("since") LocalDateTime since, Limit limit);

    @Query("select p from CommunityPost p join fetch p.category join fetch p.userAccount "
            + "where p.deletedAt is null and p.blinded = false and p.createdAt >= :since "
            + "and p.category.id = :categoryId "
            + "order by (p.likeCount * 10 + p.viewCount) desc, p.id desc")
    List<CommunityPost> findPopularByCategory(@Param("since") LocalDateTime since,
            @Param("categoryId") Long categoryId, Limit limit);

    // 삭제된 글은 "없는 글"이다 — 상세·수정·댓글·신고 경로의 404 판정 기준. 블라인드는 거르지 않는다
    // (그쪽은 404 가 아니라 410 이라 호출자가 가른다).
    @EntityGraph(attributePaths = {"category", "userAccount"})
    Optional<CommunityPost> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 반응 변경 전용 — 글 행을 비관적 쓰기 락으로 잡아 <b>같은 글의 카운터 갱신을 직렬화</b>한다.
     * 카운터는 엔티티 값에서 더하는 방식이라 잠금 없이는 동시 반응의 한쪽이 유실되고, 그 순간
     * 카운터와 반응 행 수의 일치(계약)가 깨진다. 조회 경로에 쓰지 말 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where p.id = :id and p.deletedAt is null")
    Optional<CommunityPost> findWithLockByIdAndDeletedAtIsNull(@Param("id") Long id);

    // 원자 증가 — read-modify-write 면 서로 다른 계정의 동시 조회 N 건이 N 보다 적게 반영된다
    @Modifying
    @Query("update CommunityPost p set p.viewCount = p.viewCount + 1 where p.id = :id")
    int increaseViewCount(@Param("id") Long id);

    // 댓글 작성·삭제·블라인드가 쓰는 원자 조정. 0 아래로 내려가지 않게 DB 단에서 막는다.
    @Modifying
    @Query("update CommunityPost p set p.commentCount = "
            + "case when p.commentCount + :delta < 0 then 0 else p.commentCount + :delta end "
            + "where p.id = :id")
    int adjustCommentCount(@Param("id") Long id, @Param("delta") long delta);

    /**
     * 작성자를 통째로 다른 계정으로 넘긴다 — 호출자는 만료 데이터 정리 하나뿐이다
     * ({@code ChatRepository.reassignSender} 와 같은 정책). 삭제·블라인드 행도 거르지 않는다:
     * 하나라도 남으면 NO ACTION FK 가 계정 삭제를 막는다.
     *
     * @return 이관된 글 수
     */
    @Modifying
    @Query("update CommunityPost p set p.userAccount = :newOwner where p.userAccount.id = :previousOwnerId")
    int reassignAuthor(@Param("previousOwnerId") Long previousOwnerId,
            @Param("newOwner") UserAccount newOwner);
}
