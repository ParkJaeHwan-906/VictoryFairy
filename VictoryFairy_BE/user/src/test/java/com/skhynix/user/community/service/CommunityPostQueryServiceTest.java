package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityCategory;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostImage;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityPostImageRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.community.dto.MyPostSummaryResponse;
import com.skhynix.user.community.dto.PageResponse;
import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostSort;
import com.skhynix.user.community.dto.PostSummaryResponse;
import com.skhynix.user.community.store.PostViewWindowStore;
import com.skhynix.user.community.support.CommunityFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * {@link CommunityPostQueryService} - 목록 3종, 인기 7일/5건, 상세와 조회수 창. 요구사항:
 * {@code docs/requirements/user/community.md} USER-CM-40~79.
 */
@ExtendWith(MockitoExtension.class)
class CommunityPostQueryServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), SEOUL);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0, 0);
    private static final Long AUTHOR_ID = 10L;
    private static final Long VIEWER_ID = 20L;

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityPostImageRepository postImageRepository;
    @Mock
    private CommunityPostReactionRepository postReactionRepository;
    @Mock
    private PostViewWindowStore viewWindowStore;

    private CommunityPostQueryService service;

    private final UserAccount author = CommunityFixtures.account(AUTHOR_ID, "작성자");
    private final CommunityCategory category = CommunityFixtures.category(3L, "자유게시판");

    @BeforeEach
    void setUp() {
        service = new CommunityPostQueryService(postRepository, postImageRepository, postReactionRepository,
                viewWindowStore, CLOCK);
    }

    private CommunityPost post(long id) {
        return CommunityFixtures.post(id, category, author);
    }

    private CommunityPostImage image(CommunityPost post, String endpoint, int order) {
        return CommunityPostImage.builder().post(post).endpoint(endpoint).sortOrder(order).build();
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ---------- 상세 ----------

    @Test
    @DisplayName("[AC-CM-62-1] 없거나 삭제된 글 상세는 404 COMMUNITY_POST_NOT_FOUND이고 조회 창도 조회수도 건드리지 않는다")
    void getPost_notFound_throws404WithoutCounting() {
        given(postRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPost(VIEWER_ID, 99L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        verifyNoInteractions(viewWindowStore);
        verify(postRepository, never()).increaseViewCount(anyLong());
    }

    @Test
    @DisplayName("[AC-CM-63-1, AC-CM-63-2, AC-CM-76-1] 블라인드 글 상세는 작성자 본인에게도 410이고 조회수 증가·창 생성이 없다")
    void getPost_blinded_throws410ForEveryoneWithoutCounting() {
        CommunityPost blinded = post(1L);
        blinded.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(blinded));

        assertThatThrownBy(() -> service.getPost(AUTHOR_ID, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        assertThatThrownBy(() -> service.getPost(VIEWER_ID, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        verifyNoInteractions(viewWindowStore);
        verify(postRepository, never()).increaseViewCount(anyLong());
    }

    @Test
    @DisplayName("[AC-CM-70-1, AC-CM-74-1] 창이 없던 첫 조회는 원자 증가를 1회 호출하고 응답 viewCount에 그 증가분을 더해 싣는다")
    void getPost_firstView_increasesAndReflectsIncrementInResponse() {
        CommunityPost p = post(1L);
        CommunityFixtures.set(p, "viewCount", 7L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(viewWindowStore.tryOpen(1L, VIEWER_ID)).willReturn(true);
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(VIEWER_ID, 1L)).willReturn(Optional.empty());

        PostDetailResponse response = service.getPost(VIEWER_ID, 1L);

        verify(postRepository).increaseViewCount(1L);
        assertThat(response.viewCount()).isEqualTo(8L);
        assertThat(response.isAuthor()).isFalse();
    }

    @Test
    @DisplayName("[AC-CM-72-1, AC-CM-74-1] 조회 창이 살아 있으면 증가하지 않고 응답 viewCount는 그대로다")
    void getPost_windowAlreadyOpen_doesNotIncrease() {
        CommunityPost p = post(1L);
        CommunityFixtures.set(p, "viewCount", 7L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(viewWindowStore.tryOpen(1L, VIEWER_ID)).willReturn(false);
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(VIEWER_ID, 1L)).willReturn(Optional.empty());

        PostDetailResponse response = service.getPost(VIEWER_ID, 1L);

        verify(postRepository, never()).increaseViewCount(anyLong());
        assertThat(response.viewCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("[AC-CM-75-1, AC-CM-60-3] 작성자 본인의 조회는 세지 않고 창도 만들지 않으며 isAuthor=true다")
    void getPost_author_neverCountsAndNeverOpensWindow() {
        CommunityPost p = post(1L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        PostDetailResponse response = service.getPost(AUTHOR_ID, 1L);

        verifyNoInteractions(viewWindowStore);
        verify(postRepository, never()).increaseViewCount(anyLong());
        assertThat(response.isAuthor()).isTrue();
        assertThat(response.viewCount()).isZero();
    }

    @Test
    @DisplayName("[AC-CM-78-1] Redis(조회 창 저장소) 장애면 세지 않고 상세는 정상 응답한다(읽기 fail-open, 집계 fail-closed)")
    void getPost_viewWindowStoreDown_returnsDetailWithoutCounting() {
        CommunityPost p = post(1L);
        CommunityFixtures.set(p, "viewCount", 4L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(viewWindowStore.tryOpen(1L, VIEWER_ID)).willThrow(new IllegalStateException("redis down"));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(VIEWER_ID, 1L)).willReturn(Optional.empty());

        PostDetailResponse response = service.getPost(VIEWER_ID, 1L);

        verify(postRepository, never()).increaseViewCount(anyLong());
        assertThat(response.viewCount()).isEqualTo(4L);
        assertThat(response.postId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[AC-CM-60-1, AC-CM-60-2, AC-CM-61-1, AC-CM-28-1] 상세는 본문을 가공하지 않고, 이미지는 sortOrder 순, 내 반응은 저장된 type으로 싣는다")
    void getPost_mapsContentImagesAndMyReaction() {
        CommunityPost p = post(1L);
        CommunityFixtures.set(p, "content", "  앞뒤 공백\n줄바꿈 <b>태그</b>  ");
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(viewWindowStore.tryOpen(1L, VIEWER_ID)).willReturn(false);
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of(
                image(p, "community/a.jpg", 0), image(p, "community/b.png", 1)));
        CommunityPostReaction like = CommunityPostReaction.builder().post(p).type(ReactionType.LIKE).build();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(VIEWER_ID, 1L)).willReturn(Optional.of(like));

        PostDetailResponse response = service.getPost(VIEWER_ID, 1L);

        assertThat(response.content()).isEqualTo("  앞뒤 공백\n줄바꿈 <b>태그</b>  ");
        assertThat(response.imageUrls()).containsExactly("community/a.jpg", "community/b.png");
        assertThat(response.myReaction()).isEqualTo(ReactionType.LIKE);
        assertThat(response.categoryId()).isEqualTo(3L);
        assertThat(response.categoryName()).isEqualTo("자유게시판");
        assertThat(response.author().nickname()).isEqualTo("작성자");
    }

    @Test
    @DisplayName("[AC-CM-60-2, AC-CM-29-2, AC-CM-21-1] 반응이 없으면 myReaction=null, 이미지가 없으면 빈 배열, 새 글의 카운트는 전부 0이다")
    void getPost_noReactionNoImage_nullReactionEmptyImagesZeroCounts() {
        CommunityPost p = post(1L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(p));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        PostDetailResponse response = service.getPost(AUTHOR_ID, 1L);

        assertThat(response.myReaction()).isNull();
        assertThat(response.imageUrls()).isEmpty();
        assertThat(response.viewCount()).isZero();
        assertThat(response.likeCount()).isZero();
        assertThat(response.dislikeCount()).isZero();
        assertThat(response.commentCount()).isZero();
    }

    // ---------- 전체 목록 ----------

    @Test
    @DisplayName("[AC-CM-42-1, AC-CM-46-2] categoryId 생략 + sort 기본 latest면 전체 조회 메서드를 id 내림차순 Pageable로 호출한다")
    void getPosts_noCategory_usesAllQueryWithSortFromEnum() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findAllByDeletedAtIsNullAndBlindedFalse(captor.capture()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getPosts(null, PostSort.latest, 0, 20);

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort()).isEqualTo(PostSort.latest.sort());
        verify(postRepository, never()).findAllByCategory_IdAndDeletedAtIsNullAndBlindedFalse(any(), any());
    }

    @Test
    @DisplayName("[AC-CM-46-1, AC-CM-47-1, AC-CM-43-1, AC-CM-44-1] categoryId가 있으면 카테고리 조회 메서드를 쓰고 sort=likes/views는 각 Sort를 그대로 넘긴다")
    void getPosts_withCategoryAndSort_usesCategoryQueryWithMatchingSort() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findAllByCategory_IdAndDeletedAtIsNullAndBlindedFalse(eq(999L), captor.capture()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

        PageResponse<PostSummaryResponse> response = service.getPosts(999L, PostSort.likes, 1, 5);
        service.getPosts(999L, PostSort.views, 0, 5);

        assertThat(captor.getAllValues().get(0).getSort()).isEqualTo(PostSort.likes.sort());
        assertThat(captor.getAllValues().get(1).getSort()).isEqualTo(PostSort.views.sort());
        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verify(postRepository, never()).findAllByDeletedAtIsNullAndBlindedFalse(any());
    }

    @Test
    @DisplayName("[AC-CM-40-1, AC-CM-41-3, AC-CM-6-1] 페이지 메타(page/size/totalElements/totalPages/hasNext)를 싣고 썸네일은 첫 이미지 EP(없으면 null)이며 이미지 조회는 IN 한 번이다")
    void getPosts_mapsPageMetaAndThumbnails() {
        CommunityPost withImages = post(2L);
        CommunityPost noImage = post(1L);
        given(postRepository.findAllByDeletedAtIsNullAndBlindedFalse(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(withImages, noImage), PageRequest.of(0, 2), 22));
        given(postImageRepository.findAllByPost_IdInOrderBySortOrderAsc(List.of(2L, 1L))).willReturn(List.of(
                image(withImages, "community/first.jpg", 0), image(withImages, "community/second.jpg", 1)));

        PageResponse<PostSummaryResponse> response = service.getPosts(null, PostSort.latest, 0, 2);

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(22);
        assertThat(response.totalPages()).isEqualTo(11);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.content()).extracting(PostSummaryResponse::postId).containsExactly(2L, 1L);
        assertThat(response.content().get(0).thumbnailUrl()).isEqualTo("community/first.jpg");
        assertThat(response.content().get(1).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("[AC-CM-47-1] 빈 페이지면 이미지 조회 쿼리를 내지 않는다")
    void getPosts_emptyPage_skipsImageQuery() {
        given(postRepository.findAllByDeletedAtIsNullAndBlindedFalse(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.getPosts(null, PostSort.latest, 0, 20);

        verifyNoInteractions(postImageRepository);
    }

    @Test
    @DisplayName("[AC-CM-48-1] 목록 조회는 조회 창도 조회수 증가도 건드리지 않는다")
    void getPosts_neverTouchesViewCount() {
        given(postRepository.findAllByDeletedAtIsNullAndBlindedFalse(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(post(1L)), PageRequest.of(0, 20), 1));
        given(postImageRepository.findAllByPost_IdInOrderBySortOrderAsc(List.of(1L))).willReturn(List.of());

        service.getPosts(null, PostSort.latest, 0, 20);

        verifyNoInteractions(viewWindowStore);
        verify(postRepository, never()).increaseViewCount(anyLong());
    }

    // ---------- 인기 ----------

    @Test
    @DisplayName("[AC-CM-50-1, AC-CM-50-2] 인기 목록은 Clock 기준 7일 전(2026-10-01T12:00)과 Limit 5로 조회한다")
    void getPopularPosts_usesSevenDayWindowFromClockAndLimitFive() {
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        given(postRepository.findPopular(since.capture(), limit.capture())).willReturn(List.of());

        service.getPopularPosts(null);

        assertThat(since.getValue()).isEqualTo(NOW.minusDays(7));
        assertThat(limit.getValue().max()).isEqualTo(5);
        verify(postRepository, never()).findPopularByCategory(any(), any(), any());
    }

    @Test
    @DisplayName("[AC-CM-54-1, AC-CM-54-3] categoryId가 있으면 카테고리 전용 인기 쿼리를 같은 7일/5건으로 호출한다(없는 카테고리는 빈 배열)")
    void getPopularPosts_withCategory_usesCategoryQuery() {
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        given(postRepository.findPopularByCategory(since.capture(), eq(999L), limit.capture()))
                .willReturn(List.of());

        List<PostSummaryResponse> result = service.getPopularPosts(999L);

        assertThat(result).isEmpty();
        assertThat(since.getValue()).isEqualTo(NOW.minusDays(7));
        assertThat(limit.getValue().max()).isEqualTo(5);
        verify(postRepository, never()).findPopular(any(), any());
    }

    @Test
    @DisplayName("[AC-CM-53-1, AC-CM-55-1, AC-CM-57-1] 후보가 5건 미만이면 있는 만큼(리포지토리 순서 그대로) 반환하고 조회수에 손대지 않는다")
    void getPopularPosts_returnsRepositoryOrderUntouched() {
        CommunityPost first = post(5L);
        CommunityPost second = post(3L);
        given(postRepository.findPopular(any(), any(Limit.class))).willReturn(List.of(first, second));
        given(postImageRepository.findAllByPost_IdInOrderBySortOrderAsc(List.of(5L, 3L)))
                .willReturn(List.of(image(second, "community/t.jpg", 0)));

        List<PostSummaryResponse> result = service.getPopularPosts(null);

        assertThat(result).extracting(PostSummaryResponse::postId).containsExactly(5L, 3L);
        assertThat(result.get(0).thumbnailUrl()).isNull();
        assertThat(result.get(1).thumbnailUrl()).isEqualTo("community/t.jpg");
        verifyNoInteractions(viewWindowStore);
        verify(postRepository, never()).increaseViewCount(anyLong());
    }

    @Test
    @DisplayName("[AC-CM-53-2] 후보가 0건이면 null이 아닌 빈 리스트를 반환한다")
    void getPopularPosts_noCandidates_returnsEmptyList() {
        given(postRepository.findPopular(any(), any(Limit.class))).willReturn(List.of());

        assertThat(service.getPopularPosts(null)).isNotNull().isEmpty();
    }

    // ---------- 내 글 ----------

    @Test
    @DisplayName("[AC-CM-65-1, AC-CM-66-1, AC-CM-67-1] 내 글은 요청자 id로 정렬 없는 PageRequest를 쓰고 blinded 플래그를 싣는다(블라인드 포함)")
    void getMyPosts_includesBlindedFlag_andIsNotSortable() {
        CommunityPost normal = post(2L);
        CommunityPost blinded = post(1L);
        blinded.blind();
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        given(postRepository.findAllByUserAccount_IdAndDeletedAtIsNullOrderByIdDesc(eq(AUTHOR_ID), captor.capture()))
                .willReturn(new PageImpl<>(List.of(normal, blinded), PageRequest.of(0, 20), 2));
        given(postImageRepository.findAllByPost_IdInOrderBySortOrderAsc(List.of(2L, 1L))).willReturn(List.of());

        PageResponse<MyPostSummaryResponse> response = service.getMyPosts(AUTHOR_ID, 0, 20);

        assertThat(captor.getValue().getSort().isUnsorted()).isTrue();
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.content()).extracting(MyPostSummaryResponse::blinded).containsExactly(false, true);
    }
}
