package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityCommentImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityCommentImageRepository extends JpaRepository<CommunityCommentImage, Long> {

    List<CommunityCommentImage> findAllByComment_IdOrderBySortOrderAsc(Long commentId);

    // 댓글 한 페이지(답글 포함)의 이미지를 IN 한 방으로
    List<CommunityCommentImage> findAllByComment_IdInOrderBySortOrderAsc(Collection<Long> commentIds);

    // 게시글 삭제 뒤 그 글의 댓글·답글 이미지 객체까지 best-effort 로 지우기 위한 EP 수집
    @Query("select i.endpoint from CommunityCommentImage i where i.comment.post.id = :postId")
    List<String> findEndpointsByPostId(@Param("postId") Long postId);

    @Modifying
    @Query("delete from CommunityCommentImage i where i.comment.id = :commentId")
    int deleteAllByCommentId(@Param("commentId") Long commentId);
}
