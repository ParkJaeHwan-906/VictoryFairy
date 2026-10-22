package com.skhynix.domain.community.repository;

import com.skhynix.domain.community.entity.CommunityPostReaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityPostReactionRepository extends JpaRepository<CommunityPostReaction, Long> {

    // Optional 단건인 근거는 uk_community_post_reactions_account_post 다
    Optional<CommunityPostReaction> findByUserAccount_IdAndPost_Id(Long userAccountId, Long postId);
}
