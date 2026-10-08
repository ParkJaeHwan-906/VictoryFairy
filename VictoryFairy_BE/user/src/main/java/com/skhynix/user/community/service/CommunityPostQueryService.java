package com.skhynix.user.community.service;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostImage;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityPostImageRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.user.community.dto.MyPostSummaryResponse;
import com.skhynix.user.community.dto.PageResponse;
import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostSort;
import com.skhynix.user.community.dto.PostSummaryResponse;
import com.skhynix.user.community.store.PostViewWindowStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시글 조회 4종. 클래스 레벨 {@code readOnly} 지만 <b>상세는 예외</b>다 — 조회수 증가라는 쓰기가 GET 의
 * 부수효과로 붙는다(별도 POST 를 두면 한 번만 부르는 클라이언트는 영영 안 센다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityPostQueryService {

    private static final Logger log = LoggerFactory.getLogger(CommunityPostQueryService.class);

    /** 인기 집계 기간. 기간 없이 누적하면 초기 글이 영구 상단에 고정된다. */
    private static final int POPULAR_WINDOW_DAYS = 7;
    private static final int POPULAR_LIMIT = 5;

    private final CommunityPostRepository postRepository;
    private final CommunityPostImageRepository postImageRepository;
    private final CommunityPostReactionRepository postReactionRepository;
    private final PostViewWindowStore viewWindowStore;
    private final Clock clock;

    // 없는 categoryId 는 404 가 아니라 빈 페이지 — 조회 필터에 존재 검증을 붙이지 않는 컨벤션(/players?teamId=)
    public PageResponse<PostSummaryResponse> getPosts(Long categoryId, PostSort sort, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, sort.sort());
        Page<CommunityPost> posts = categoryId == null
                ? postRepository.findAllByDeletedAtIsNullAndBlindedFalse(pageable)
                : postRepository.findAllByCategory_IdAndDeletedAtIsNullAndBlindedFalse(categoryId, pageable);
        Map<Long, String> thumbnails = thumbnailsOf(posts.getContent());
        return PageResponse.of(posts,
                post -> PostSummaryResponse.of(post, thumbnails.get(post.getId())));
    }

    // 전체 목록에서 제외하지 않는다 — 두 API 는 독립이고 중복 제거는 프론트 몫
    public List<PostSummaryResponse> getPopularPosts(Long categoryId) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(POPULAR_WINDOW_DAYS);
        Limit limit = Limit.of(POPULAR_LIMIT);
        List<CommunityPost> posts = categoryId == null
                ? postRepository.findPopular(since, limit)
                : postRepository.findPopularByCategory(since, categoryId, limit);
        Map<Long, String> thumbnails = thumbnailsOf(posts);
        return posts.stream()
                .map(post -> PostSummaryResponse.of(post, thumbnails.get(post.getId())))
                .toList();
    }

    // 블라인드 포함(마이페이지에서 본인에게 알린다), 정렬은 최신순뿐이라 sort 파라미터가 없다
    public PageResponse<MyPostSummaryResponse> getMyPosts(Long userAccountId, int page, int size) {
        Page<CommunityPost> posts = postRepository
                .findAllByUserAccount_IdAndDeletedAtIsNullOrderByIdDesc(userAccountId,
                        PageRequest.of(page, size));
        Map<Long, String> thumbnails = thumbnailsOf(posts.getContent());
        return PageResponse.of(posts,
                post -> MyPostSummaryResponse.of(post, thumbnails.get(post.getId())));
    }

    /**
     * 상세 + 조회수. 404·410 판정이 증가보다 앞이라 실패로 끝나는 요청은 세지 않는다. 작성자 본인은 세지도
     * 창을 열지도 않는다. 증가는 원자 UPDATE 이고 응답의 {@code viewCount} 는 그 증가분을 더해 싣는다
     * (엔티티는 UPDATE 전에 읽혀 옛 값을 들고 있다).
     */
    @Transactional
    public PostDetailResponse getPost(Long userAccountId, Long postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        if (post.isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        boolean isAuthor = post.getUserAccount().getId().equals(userAccountId);

        long viewCount = post.getViewCount();
        if (!isAuthor && openViewWindow(postId, userAccountId)) {
            postRepository.increaseViewCount(postId);
            viewCount++;
        }

        List<String> imageUrls = postImageRepository.findAllByPost_IdOrderBySortOrderAsc(postId).stream()
                .map(CommunityPostImage::getEndpoint)
                .toList();
        ReactionType myReaction = postReactionRepository
                .findByUserAccount_IdAndPost_Id(userAccountId, postId)
                .map(CommunityPostReaction::getType)
                .orElse(null);
        return PostDetailResponse.of(post, imageUrls, viewCount, myReaction, isAuthor);
    }

    /**
     * 읽기는 fail-open, 집계는 fail-closed — Redis 에 닿지 못하면 상세는 200 으로 내보내되 세지 않는다.
     * 어뷰징 판정을 못 하는 상태에서 세면 그 동안 무제한 집계가 된다.
     */
    private boolean openViewWindow(Long postId, Long userAccountId) {
        try {
            return viewWindowStore.tryOpen(postId, userAccountId);
        } catch (RuntimeException e) {
            log.error("조회 창 저장소 접근 실패 — 이번 조회는 세지 않는다: postId={}", postId, e);
            return false;
        }
    }

    // 한 페이지의 첫 이미지를 IN 한 방으로. sort_order 오름차순이라 먼저 온 것이 첫 이미지다.
    private Map<Long, String> thumbnailsOf(List<CommunityPost> posts) {
        if (posts.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = posts.stream().map(CommunityPost::getId).toList();
        Map<Long, String> thumbnails = new HashMap<>();
        for (CommunityPostImage image : postImageRepository.findAllByPost_IdInOrderBySortOrderAsc(ids)) {
            thumbnails.putIfAbsent(image.getPost().getId(), image.getEndpoint());
        }
        return thumbnails;
    }
}
