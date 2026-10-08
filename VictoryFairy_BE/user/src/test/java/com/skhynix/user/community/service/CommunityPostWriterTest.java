package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
import com.skhynix.domain.community.repository.CommunityCategoryRepository;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityPostImageRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.service.CommunityPostWriter.UpdateResult;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CommunityPostWriter} - 게시글 쓰기 트랜잭션 단위(검증 순서 404/403/410/카테고리 404, 전체 교체, 소프트 삭제).
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-20~30, 80~97.
 */
@ExtendWith(MockitoExtension.class)
class CommunityPostWriterTest {

    private static final Long AUTHOR_ID = 10L;
    private static final Long OTHER_ID = 20L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityPostImageRepository postImageRepository;
    @Mock
    private CommunityCommentImageRepository commentImageRepository;
    @Mock
    private CommunityPostReactionRepository postReactionRepository;
    @Mock
    private CommunityCategoryRepository categoryRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private CommunityPostWriter writer;

    private final UserAccount author = CommunityFixtures.account(AUTHOR_ID, "작성자");
    private final CommunityCategory oldCategory = CommunityFixtures.category(1L, "자유게시판");
    private final CommunityCategory newCategory = CommunityFixtures.category(2L, "LG 트윈스");
    private CommunityPost post;

    @BeforeEach
    void setUp() {
        writer = new CommunityPostWriter(postRepository, postImageRepository, commentImageRepository,
                postReactionRepository, categoryRepository, userAccountRepository, CLOCK);
        post = CommunityFixtures.post(1L, oldCategory, author);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    private CommunityPostImage image(String endpoint, int order) {
        return CommunityPostImage.builder().post(post).endpoint(endpoint).sortOrder(order).build();
    }

    // ---------- 작성 ----------

    @Test
    @DisplayName("[AC-CM-27-1] prepareCreate: 없는 카테고리는 404 COMMUNITY_CATEGORY_NOT_FOUND다")
    void prepareCreate_unknownCategory_throws404() {
        given(categoryRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareCreate(999L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-20-2, AC-CM-28-1, AC-CM-21-1, AC-CM-61-1] create: 요청자 명의로 받은 그대로 저장하고 이미지는 요청 순서대로 sortOrder 0..n-1을 부여하며 id를 반환한다")
    void create_savesPostAsIsWithOrderedImages() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(userAccountRepository.findById(AUTHOR_ID)).willReturn(Optional.of(author));
        given(postRepository.save(any(CommunityPost.class))).willAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            CommunityFixtures.set(p, "id", 77L);
            return p;
        });

        Long id = writer.create(AUTHOR_ID, 1L, " 제목 ", "\n 본문 \n", List.of("community/a.jpg", "community/b.jpg"));

        assertThat(id).isEqualTo(77L);
        ArgumentCaptor<CommunityPost> saved = ArgumentCaptor.forClass(CommunityPost.class);
        verify(postRepository).save(saved.capture());
        CommunityPost p = saved.getValue();
        assertThat(p.getTitle()).isEqualTo(" 제목 ");
        assertThat(p.getContent()).isEqualTo("\n 본문 \n");
        assertThat(p.getUserAccount()).isSameAs(author);
        assertThat(p.getCategory()).isSameAs(oldCategory);
        assertThat(p.getViewCount()).isZero();
        assertThat(p.isBlinded()).isFalse();
        assertThat(p.getDeletedAt()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommunityPostImage>> images = ArgumentCaptor.forClass(List.class);
        verify(postImageRepository).saveAll(images.capture());
        assertThat(images.getValue()).extracting(CommunityPostImage::getEndpoint)
                .containsExactly("community/a.jpg", "community/b.jpg");
        assertThat(images.getValue()).extracting(CommunityPostImage::getSortOrder).containsExactly(0, 1);
    }

    @Test
    @DisplayName("[AC-CM-29-2] create: 이미지가 없으면 빈 목록으로 saveAll 한다(예외 없음)")
    void create_withoutImages_savesEmptyList() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(userAccountRepository.findById(AUTHOR_ID)).willReturn(Optional.of(author));
        given(postRepository.save(any(CommunityPost.class))).willAnswer(inv -> inv.getArgument(0));

        writer.create(AUTHOR_ID, 1L, "t", "c", List.of());

        verify(postImageRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("create: 요청자 계정이 없으면 401 UNAUTHENTICATED이고 글은 저장되지 않는다")
    void create_accountMissing_throwsUnauthenticated() {
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(userAccountRepository.findById(AUTHOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.create(AUTHOR_ID, 1L, "t", "c", List.of()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verify(postRepository, never()).save(any());
    }

    // ---------- 수정 선행 검사 ----------

    @Test
    @DisplayName("[AC-CM-83-1] prepareUpdate: 없거나 삭제된 글은 404 COMMUNITY_POST_NOT_FOUND이고 카테고리는 조회하지 않는다")
    void prepareUpdate_postNotFound_throws404() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 1L, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        verifyNoInteractions(categoryRepository);
    }

    @Test
    @DisplayName("[AC-CM-82-1, AC-CM-82-2] prepareUpdate: 작성자가 아니면 403이고, 카테고리 존재 검사(404)보다 먼저다")
    void prepareUpdate_notAuthor_throws403BeforeCategoryCheck() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> writer.prepareUpdate(OTHER_ID, 1L, 999L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
        verifyNoInteractions(categoryRepository);
    }

    @Test
    @DisplayName("[AC-CM-84-1] prepareUpdate: 블라인드 글은 작성자 본인도 410 COMMUNITY_POST_BLINDED다(수정으로 블라인드 우회 불가)")
    void prepareUpdate_blindedByAuthor_throws410() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 1L, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
    }

    @Test
    @DisplayName("[AC-CM-81-2, AC-CM-82-2] prepareUpdate: 글/작성자/블라인드 검사를 통과한 뒤 없는 카테고리는 404 COMMUNITY_CATEGORY_NOT_FOUND다")
    void prepareUpdate_unknownCategory_throws404AfterAuthorCheck() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 1L, 999L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-87-1] prepareUpdate: 이 글에 현재 붙은 확정 EP를 sortOrder 순으로 돌려준다(수정 요청이 유지할 수 있는 값의 집합)")
    void prepareUpdate_returnsCurrentEndpoints() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(2L)).willReturn(Optional.of(newCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/a.jpg", 0), image("community/b.jpg", 1)));

        assertThat(writer.prepareUpdate(AUTHOR_ID, 1L, 2L)).containsExactly("community/a.jpg", "community/b.jpg");
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("[AC-CM-80-1, AC-CM-85-1, AC-CM-87-1] update: 제목·본문·카테고리·이미지를 전부 교체하되 카운터·작성자·createdAt은 유지하고, 빠진 EP만 removed로 돌려준다")
    void update_replacesFieldsKeepsCountersAndReportsRemovedEndpoints() {
        CommunityFixtures.set(post, "viewCount", 10L);
        CommunityFixtures.set(post, "likeCount", 2L);
        CommunityFixtures.set(post, "commentCount", 4L);
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(2L)).willReturn(Optional.of(newCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/a.jpg", 0), image("community/b.jpg", 1)));
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        UpdateResult result = writer.update(AUTHOR_ID, 1L, 2L, "수정", "본문",
                List.of("community/b.jpg", "community/c.jpg"));

        assertThat(post.getTitle()).isEqualTo("수정");
        assertThat(post.getContent()).isEqualTo("본문");
        assertThat(post.getCategory()).isSameAs(newCategory);
        assertThat(post.getUserAccount()).isSameAs(author);
        assertThat(post.getCreatedAt()).isEqualTo(CommunityFixtures.CREATED_AT);
        assertThat(result.removedEndpoints()).containsExactly("community/a.jpg");
        assertThat(result.detail().viewCount()).isEqualTo(10L);
        assertThat(result.detail().likeCount()).isEqualTo(2L);
        assertThat(result.detail().commentCount()).isEqualTo(4L);
        assertThat(result.detail().imageUrls()).containsExactly("community/b.jpg", "community/c.jpg");
        assertThat(result.detail().isAuthor()).isTrue();
        assertThat(result.detail().categoryId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("update: 기존 이미지 행을 지운 뒤 새 목록을 저장하고, 응답을 만들기 전에 flush 한다")
    void update_deletesOldImagesThenSavesNewThenFlushes() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(2L)).willReturn(Optional.of(newCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        writer.update(AUTHOR_ID, 1L, 2L, "t", "c", List.of("community/c.jpg"));

        InOrder order = inOrder(postImageRepository, postRepository);
        order.verify(postImageRepository).deleteAllByPostId(1L);
        order.verify(postImageRepository).saveAll(any());
        order.verify(postRepository).flush();
    }

    @Test
    @DisplayName("[AC-CM-86-1] update: 제목·본문·카테고리가 그대로이고 이미지만 바뀌어도 updatedAt이 Clock 시각(KST)으로 갱신된다"
            + " — updatedAt의 출처는 edit에 넘기는 Clock 값 하나다(엔티티에 @UpdateTimestamp 없음)")
    void update_imageOnlyChange_stillRefreshesUpdatedAt() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/a.jpg", 0)));
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        UpdateResult result = writer.update(AUTHOR_ID, 1L, 1L, post.getTitle(), post.getContent(), List.of());

        assertThat(post.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 12, 0, 0));
        assertThat(post.getCreatedAt()).isEqualTo(CommunityFixtures.CREATED_AT);
        assertThat(result.detail().updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 12, 0, 0));
        assertThat(result.removedEndpoints()).containsExactly("community/a.jpg");
    }

    @Test
    @DisplayName("[AC-CM-87-2] update: 이미지 배열을 []로 보내면 기존 EP 전부가 removed가 된다")
    void update_emptyImages_removesAllExisting() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/a.jpg", 0), image("community/b.jpg", 1)));
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        UpdateResult result = writer.update(AUTHOR_ID, 1L, 1L, "t", "c", List.of());

        assertThat(result.removedEndpoints()).containsExactly("community/a.jpg", "community/b.jpg");
        assertThat(result.detail().imageUrls()).isEmpty();
    }

    @Test
    @DisplayName("[AC-CM-61-2] update: 순서만 바꾼 [c,a]는 제거 없이 요청 순서가 최종 순서가 된다")
    void update_reorderOnly_noRemovalAndRequestedOrder() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/a.jpg", 0), image("community/c.jpg", 1)));
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.empty());

        UpdateResult result = writer.update(AUTHOR_ID, 1L, 1L, "t", "c",
                List.of("community/c.jpg", "community/a.jpg"));

        assertThat(result.removedEndpoints()).isEmpty();
        assertThat(result.detail().imageUrls()).containsExactly("community/c.jpg", "community/a.jpg");
    }

    @Test
    @DisplayName("update: 응답의 myReaction은 요청자의 저장된 반응이다")
    void update_responseCarriesMyReaction() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(categoryRepository.findById(1L)).willReturn(Optional.of(oldCategory));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR_ID, 1L)).willReturn(Optional.of(
                CommunityPostReaction.builder().post(post).type(ReactionType.DISLIKE).build()));

        UpdateResult result = writer.update(AUTHOR_ID, 1L, 1L, "t", "c", List.of());

        assertThat(result.detail().myReaction()).isEqualTo(ReactionType.DISLIKE);
    }

    @Test
    @DisplayName("[AC-CM-82-1] update: 작성자가 아니면 403이고 글·이미지는 그대로다")
    void update_notAuthor_throws403WithoutChanges() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> writer.update(OTHER_ID, 1L, 2L, "해킹", "c", List.of()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
        assertThat(post.getTitle()).isEqualTo("제목1");
        verifyNoInteractions(postImageRepository);
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("[AC-CM-90-2] delete: 소프트 삭제로 deletedAt에 Clock 시각(KST)이 기록되고 행은 지우지 않는다")
    void delete_softDeletesWithClockTime() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(commentImageRepository.findEndpointsByPostId(1L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 1L);

        assertThat(post.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 12, 0, 0));
        verify(postRepository, never()).delete(any());
        verify(postRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("[AC-CM-96-3, AC-CM-97-1] delete: 이미지 테이블 행은 지우지 않는다(소프트 삭제라 행 보존, 객체만 커밋 뒤 삭제)")
    void delete_keepsImageRows() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(commentImageRepository.findEndpointsByPostId(1L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 1L);

        verify(postImageRepository, never()).deleteAllByPostId(anyLong());
        verify(postImageRepository, never()).deleteAll();
        verify(commentImageRepository, never()).deleteAllByCommentId(anyLong());
    }

    @Test
    @DisplayName("[AC-CM-96-1] delete: 글 이미지와 그 글 댓글·답글 이미지의 확정 EP를 모두 돌려준다(커밋 뒤 삭제용)")
    void delete_returnsPostAndCommentImageEndpoints() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L))
                .willReturn(List.of(image("community/p1.jpg", 0)));
        given(commentImageRepository.findEndpointsByPostId(1L)).willReturn(List.of("community/c1.jpg"));

        assertThat(writer.delete(AUTHOR_ID, 1L)).containsExactly("community/p1.jpg", "community/c1.jpg");
    }

    @Test
    @DisplayName("[AC-CM-94-1] delete: 블라인드 글도 작성자는 삭제할 수 있다")
    void delete_blindedPostByAuthor_isAllowed() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(postImageRepository.findAllByPost_IdOrderBySortOrderAsc(1L)).willReturn(List.of());
        given(commentImageRepository.findEndpointsByPostId(1L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 1L);

        assertThat(post.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("[AC-CM-92-1] delete: 작성자가 아니면 403이고 deletedAt은 null로 남는다")
    void delete_notAuthor_throws403() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> writer.delete(OTHER_ID, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
        assertThat(post.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("[AC-CM-93-1] delete: 이미 삭제된 글(조회에서 빠짐)의 재삭제는 멱등 204가 아니라 404다")
    void delete_alreadyDeleted_throws404() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.delete(AUTHOR_ID, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }
}
