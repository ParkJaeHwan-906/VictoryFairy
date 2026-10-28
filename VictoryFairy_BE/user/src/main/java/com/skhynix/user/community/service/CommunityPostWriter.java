package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityCategory;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostImage;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityCategoryRepository;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityPostImageRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.dto.PostDetailResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시글 쓰기의 <b>트랜잭션 단위</b>. 지휘하는 쪽({@link CommunityPostCommandService})은 트랜잭션을 열지 않고
 * S3 이동을 사이에 끼운다 — 클래스를 나눈 이유는 {@code AccountProfileImageWriter} 와 같다(프록시 +
 * 외부 호출을 트랜잭션 밖에 두기).
 *
 * <p>{@code prepare*} 는 S3 를 건드리기 전에 상태코드를 확정하는 선행 검사이고, {@code create/update} 는
 * 같은 검사를 <b>다시</b> 한다 — 그 사이에 글이 삭제·블라인드될 수 있어서다. 중복이 아니라 순서 계약
 * (400 → 글 404 → 작성자 403 → 블라인드 410 → 카테고리 404 → EP 400 → 저장)의 대가다.
 */
@Service
@RequiredArgsConstructor
public class CommunityPostWriter {

    /** 수정 결과 — 상세와, 목록에서 빠져 커밋 뒤 지워야 할 확정 EP. */
    public record UpdateResult(PostDetailResponse detail, List<String> removedEndpoints) {
    }

    private final CommunityPostRepository postRepository;
    private final CommunityPostImageRepository postImageRepository;
    private final CommunityCommentImageRepository commentImageRepository;
    private final CommunityPostReactionRepository postReactionRepository;
    private final CommunityCategoryRepository categoryRepository;
    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void prepareCreate(Long categoryId) {
        requireCategory(categoryId);
    }

    /** @return 이 글에 지금 붙어 있는 확정 EP(요청 순서) — 수정 요청이 유지할 수 있는 값의 집합 */
    @Transactional(readOnly = true)
    public List<String> prepareUpdate(Long userAccountId, Long postId, Long categoryId) {
        CommunityPost post = requireEditable(userAccountId, postId);
        requireCategory(categoryId);
        return endpointsOf(post.getId());
    }

    @Transactional
    public Long create(Long userAccountId, Long categoryId, String title, String content,
            List<String> endpoints) {
        CommunityCategory category = requireCategory(categoryId);
        UserAccount author = requireAccount(userAccountId);
        // 커뮤니티의 모든 시각은 이 Clock 하나에서 나온다(엔티티에 @CreationTimestamp 없음 — CommunityPost.createdAt 참고)
        LocalDateTime now = LocalDateTime.now(clock);
        CommunityPost post = postRepository.save(CommunityPost.builder()
                .category(category)
                .userAccount(author)
                .title(title)
                .content(content)
                .createdAt(now)
                .build());
        saveImages(post, endpoints, now);
        return post.getId();
    }

    @Transactional
    public UpdateResult update(Long userAccountId, Long postId, Long categoryId, String title,
            String content, List<String> endpoints) {
        CommunityPost post = requireEditable(userAccountId, postId);
        CommunityCategory category = requireCategory(categoryId);

        List<String> removed = new ArrayList<>(endpointsOf(postId));
        removed.removeAll(endpoints);

        // updatedAt 의 유일한 출처는 이 Clock 값이다(엔티티에 @UpdateTimestamp 가 없다 — 반응 카운터 변경이
        // "수정됨"으로 보이지 않게 하기 위해서). 이미지만 바뀐 수정도 여기서 갱신된다.
        LocalDateTime now = LocalDateTime.now(clock);
        post.edit(category, title, content, now);
        postImageRepository.deleteAllByPostId(postId);
        saveImages(post, endpoints, now);
        // 응답을 만들기 전에 DB 에 반영 — 제약 위반 등 쓰기 실패가 응답 조립 뒤가 아니라 여기서 드러나게 한다
        postRepository.flush();

        ReactionType myReaction = postReactionRepository
                .findByUserAccount_IdAndPost_Id(userAccountId, postId)
                .map(CommunityPostReaction::getType)
                .orElse(null);
        PostDetailResponse detail = PostDetailResponse.of(post, endpoints, post.getViewCount(),
                myReaction, true);
        return new UpdateResult(detail, removed);
    }

    /**
     * 소프트 삭제. 댓글·답글 행은 손대지 않는다(접근 경로가 전부 글 404 로 막힌다). 블라인드 글도 작성자는
     * 지울 수 있다. 이미지 <b>행</b>은 남기고 객체만 커밋 뒤 지우므로 EP 를 모아 돌려준다.
     *
     * @return 글과 그 댓글·답글에 붙은 확정 EP 전부
     */
    @Transactional
    public List<String> delete(Long userAccountId, Long postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        requireAuthor(post, userAccountId);

        post.delete(LocalDateTime.now(clock));

        List<String> endpoints = new ArrayList<>(endpointsOf(postId));
        endpoints.addAll(commentImageRepository.findEndpointsByPostId(postId));
        return endpoints;
    }

    // 404 → 403 → 410 순. 블라인드 글은 작성자라도 수정으로 우회할 수 없다.
    private CommunityPost requireEditable(Long userAccountId, Long postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        requireAuthor(post, userAccountId);
        if (post.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        return post;
    }

    private void requireAuthor(CommunityPost post, Long userAccountId) {
        if (!post.getUserAccount().getId().equals(userAccountId)) {
            throw new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR);
        }
    }

    private CommunityCategory requireCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND));
    }

    // 필터가 활성 계정임을 확인한 id 라 정상 경로에서는 항상 존재한다(UserProfileService 와 같은 401 처리)
    private UserAccount requireAccount(Long userAccountId) {
        return userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    private List<String> endpointsOf(Long postId) {
        return postImageRepository.findAllByPost_IdOrderBySortOrderAsc(postId).stream()
                .map(CommunityPostImage::getEndpoint)
                .toList();
    }

    private void saveImages(CommunityPost post, List<String> endpoints, LocalDateTime now) {
        List<CommunityPostImage> images = new ArrayList<>(endpoints.size());
        for (int i = 0; i < endpoints.size(); i++) {
            images.add(CommunityPostImage.builder()
                    .post(post)
                    .endpoint(endpoints.get(i))
                    .sortOrder(i)
                    .createdAt(now)
                    .build());
        }
        postImageRepository.saveAll(images);
    }
}
