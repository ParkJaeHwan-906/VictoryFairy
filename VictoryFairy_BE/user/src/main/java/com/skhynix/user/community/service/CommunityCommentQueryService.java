package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 댓글 목록은 글 조회수를 세지 않는다 — 조회수는 상세 GET 하나만 센다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityCommentQueryService {

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommentResponseAssembler assembler;

    /**
     * 최상위 댓글 페이징(답글은 항목 안 {@code replies[]}). {@code totalElements} 는 최상위 항목 + 자리 표식 수라
     * 글의 {@code commentCount}(답글까지 센 수)와 다르다 — 같아야 한다고 맞추지 말 것.
     */
    public PageResponse<CommentResponse> getComments(Long userAccountId, Long postId, int page, int size) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        if (post.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        Page<CommunityComment> parents = commentRepository.findTopLevelPage(postId,
                PageRequest.of(page, size));
        return PageResponse.of(parents, assembler.assemble(parents.getContent(), userAccountId));
    }
}
