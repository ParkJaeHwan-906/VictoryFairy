package com.skhynix.user.community.service;

import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostRequest;
import com.skhynix.user.community.policy.CommunityImagePolicy;
import com.skhynix.user.community.service.CommunityPostWriter.UpdateResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 게시글 작성·수정·삭제의 지휘자. 이 클래스에 {@code @Transactional} 이 없는 것은 실수가 아니다 — 단계의
 * 순서가 곧 계약이다: ①선행 검사(DB) → ②이미지 검증·이동(S3) → ③쓰기(트랜잭션) → ④빠진 객체 삭제(커밋 뒤).
 *
 * <ul>
 *   <li>②가 ③보다 앞이라 ③이 실패하면 {@code community/} 고아 객체가 남을 수 있다(알려진 한계) — 반대로
 *       두면 트랜잭션이 S3 대기에 묶이고, 이동 실패가 이미 커밋된 글을 되돌릴 수 없다</li>
 *   <li>④는 반드시 ③의 커밋 뒤다. 롤백된 수정에서 글이 여전히 가리키는 객체를 지우면 안 된다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommunityPostCommandService {

    private final CommunityPostWriter writer;
    private final CommunityImageAttacher imageAttacher;
    private final CommunityImageEraser imageEraser;

    public Long create(Long userAccountId, PostRequest request) {
        writer.prepareCreate(request.categoryId());
        // 새 글에는 기존 EP 가 없다 — community/ 값 자체가 400 이다
        List<String> endpoints = imageAttacher.attach(request.imageUrlsOrEmpty(), Set.of(),
                CommunityImagePolicy.MAX_POST_IMAGES);
        return writer.create(userAccountId, request.categoryId(), request.title(), request.content(),
                endpoints);
    }

    public PostDetailResponse update(Long userAccountId, Long postId, PostRequest request) {
        Set<String> existing = new HashSet<>(
                writer.prepareUpdate(userAccountId, postId, request.categoryId()));
        List<String> endpoints = imageAttacher.attach(request.imageUrlsOrEmpty(), existing,
                CommunityImagePolicy.MAX_POST_IMAGES);
        UpdateResult result = writer.update(userAccountId, postId, request.categoryId(),
                request.title(), request.content(), endpoints);
        imageEraser.eraseQuietly(result.removedEndpoints());
        return result.detail();
    }

    public void delete(Long userAccountId, Long postId) {
        List<String> endpoints = writer.delete(userAccountId, postId);
        imageEraser.eraseQuietly(endpoints);
    }
}
