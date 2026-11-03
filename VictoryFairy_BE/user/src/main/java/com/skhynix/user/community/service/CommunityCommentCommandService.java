package com.skhynix.user.community.service;

import com.skhynix.user.community.dto.CommentItemResponse;
import com.skhynix.user.community.dto.CommentRequest;
import com.skhynix.user.community.dto.CommentUpdateRequest;
import com.skhynix.user.community.policy.CommunityImagePolicy;
import com.skhynix.user.community.service.CommunityCommentWriter.UpdateResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 댓글·답글 작성·수정·삭제의 지휘자. 단계 순서와 무트랜잭션의 이유는 {@link CommunityPostCommandService} 와 같다. */
@Service
@RequiredArgsConstructor
public class CommunityCommentCommandService {

    private final CommunityCommentWriter writer;
    private final CommunityImageAttacher imageAttacher;
    private final CommunityImageEraser imageEraser;

    public Long create(Long userAccountId, Long postId, CommentRequest request) {
        writer.prepareCreate(postId, request.parentCommentId());
        List<String> endpoints = imageAttacher.attach(request.imageUrlsOrEmpty(), Set.of(),
                CommunityImagePolicy.MAX_COMMENT_IMAGES);
        return writer.create(userAccountId, postId, request.parentCommentId(), request.content(),
                endpoints);
    }

    public CommentItemResponse update(Long userAccountId, Long commentId, CommentUpdateRequest request) {
        Set<String> existing = new HashSet<>(writer.prepareUpdate(userAccountId, commentId));
        List<String> endpoints = imageAttacher.attach(request.imageUrlsOrEmpty(), existing,
                CommunityImagePolicy.MAX_COMMENT_IMAGES);
        UpdateResult result = writer.update(userAccountId, commentId, request.content(), endpoints);
        imageEraser.eraseQuietly(result.removedEndpoints());
        return result.item();
    }

    public void delete(Long userAccountId, Long commentId) {
        imageEraser.eraseQuietly(writer.delete(userAccountId, commentId));
    }
}
