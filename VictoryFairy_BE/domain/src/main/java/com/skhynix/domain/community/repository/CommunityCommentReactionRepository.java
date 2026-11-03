package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityCommentReaction;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityCommentReactionRepository
        extends JpaRepository<CommunityCommentReaction, Long> {

    Optional<CommunityCommentReaction> findByUserAccount_IdAndComment_Id(Long userAccountId,
            Long commentId);

    // 댓글 한 페이지(답글 포함)의 내 반응을 IN 한 방으로. 호출자는 comment.id 만 읽으므로 프록시를 깨우지 않는다.
    List<CommunityCommentReaction> findAllByUserAccount_IdAndComment_IdIn(Long userAccountId,
            Collection<Long> commentIds);
}
