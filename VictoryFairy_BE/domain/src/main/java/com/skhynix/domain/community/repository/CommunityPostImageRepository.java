package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityPostImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostImageRepository extends JpaRepository<CommunityPostImage, Long> {

    List<CommunityPostImage> findAllByPost_IdOrderBySortOrderAsc(Long postId);

    // 목록 한 페이지의 썸네일(첫 이미지)을 IN 한 방으로 — 항목마다 부르면 N+1
    List<CommunityPostImage> findAllByPost_IdInOrderBySortOrderAsc(Collection<Long> postIds);

    // 전체 교체 수정이 기존 행을 비우는 데 쓴다. 엔티티를 읽지 않는 벌크 삭제.
    @Modifying
    @Query("delete from CommunityPostImage i where i.post.id = :postId")
    int deleteAllByPostId(@Param("postId") Long postId);
}
