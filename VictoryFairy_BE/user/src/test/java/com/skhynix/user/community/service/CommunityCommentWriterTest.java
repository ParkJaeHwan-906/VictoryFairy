package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentImage;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.ReplyResponse;
import com.skhynix.user.community.service.CommunityCommentWriter.UpdateResult;
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

/**
 * {@link CommunityCommentWriter} - 댓글·답글 쓰기 트랜잭션 단위. 답글 규칙(404 -> 400 DEPTH -> 410), 부모 삭제 비연쇄,
 * commentCount 증감, 수정/삭제 검증 순서. 요구사항: {@code docs/requirements/user/community.md}
 * USER-CM-100~119, 210~218.
 */
@ExtendWith(MockitoExtension.class)
class CommunityCommentWriterTest {

    private static final Long POST_ID = 1L;
    private static final Long AUTHOR_ID = 10L;
    private static final Long OTHER_ID = 20L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityCommentRepository commentRepository;
    @Mock
    private CommunityCommentImageRepository commentImageRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private CommentResponseAssembler assembler;

    private CommunityCommentWriter writer;

    private final UserAccount author = CommunityFixtures.account(AUTHOR_ID, "작성자");
    private CommunityPost post;
    private CommunityComment parent;

    @BeforeEach
    void setUp() {
        writer = new CommunityCommentWriter(postRepository, commentRepository, commentImageRepository,
                userAccountRepository, assembler, CLOCK);
        post = CommunityFixtures.post(POST_ID, CommunityFixtures.category(1L, "자유"), author);
        parent = CommunityFixtures.comment(5L, post, null, author);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    private void postFound() {
        given(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).willReturn(Optional.of(post));
    }

    private void commentFound(CommunityComment c) {
        given(commentRepository.findByIdAndDeletedAtIsNull(c.getId())).willReturn(Optional.of(c));
    }

    // 삭제는 신고와 같은 락 쿼리로 읽는다(동시 삭제·신고의 commentCount 이중 차감 방지)
    private void commentLocked(CommunityComment c) {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(c.getId())).willReturn(Optional.of(c));
    }

    private CommunityCommentImage image(CommunityComment c, String endpoint, int order) {
        return CommunityCommentImage.builder().comment(c).endpoint(endpoint).sortOrder(order).build();
    }

    // ---------- prepareCreate: 글 / 부모 규칙 ----------

    @Test
    @DisplayName("[AC-CM-103-1] 소속 글이 없거나 삭제됐으면 404 COMMUNITY_POST_NOT_FOUND다")
    void prepareCreate_postNotFound_throws404() {
        given(postRepository.findByIdAndDeletedAtIsNull(POST_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, null))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-104-1] 소속 글이 블라인드면 410 COMMUNITY_POST_BLINDED이고, 부모 검사보다 먼저다")
    void prepareCreate_postBlinded_throws410BeforeParentCheck() {
        post.blind();
        postFound();

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 999L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[AC-CM-100-3] parentCommentId가 null이면 부모 조회 없이 통과한다(최상위 댓글)")
    void prepareCreate_noParent_passesWithoutParentLookup() {
        postFound();

        writer.prepareCreate(POST_ID, null);

        verifyNoInteractions(commentRepository);
    }

    @Test
    @DisplayName("[AC-CM-115-1] 존재하지 않는(또는 삭제된) 부모는 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void prepareCreate_parentMissing_throws404() {
        postFound();
        given(commentRepository.findByIdAndDeletedAtIsNull(999999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 999999L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-115-2] 다른 게시글의 댓글을 부모로 지목하면 404 COMMUNITY_COMMENT_NOT_FOUND다(글 스코프 밖)")
    void prepareCreate_parentFromOtherPost_throws404() {
        CommunityPost other = CommunityFixtures.post(2L, CommunityFixtures.category(1L, "자유"), author);
        CommunityComment foreign = CommunityFixtures.comment(50L, other, null, author);
        postFound();
        commentFound(foreign);

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 50L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-116-1] 부모가 답글이면 400 COMMUNITY_REPLY_DEPTH_EXCEEDED다(깊이 1 고정)")
    void prepareCreate_parentIsReply_throws400Depth() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, author);
        postFound();
        commentFound(reply);

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 6L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED));
    }

    @Test
    @DisplayName("[AC-CM-117-2] 블라인드 부모에는 새 답글을 달 수 없다 - 410 COMMUNITY_COMMENT_BLINDED")
    void prepareCreate_parentBlinded_throws410() {
        parent.blind();
        postFound();
        commentFound(parent);

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_BLINDED));
    }

    @Test
    @DisplayName("[AC-CM-117-1] 삭제된 부모(자리 표식 상태 포함)는 조회에서 빠져 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void prepareCreate_parentDeleted_throws404() {
        postFound();
        given(commentRepository.findByIdAndDeletedAtIsNull(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-116-1] 판정 순서는 404 -> 400 -> 410이다: 블라인드 상태의 답글을 부모로 지목하면 410이 아니라 400 DEPTH다")
    void prepareCreate_blindedReplyAsParent_depthWinsOverBlinded() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, author);
        reply.blind();
        postFound();
        commentFound(reply);

        assertThatThrownBy(() -> writer.prepareCreate(POST_ID, 6L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED));
    }

    @Test
    @DisplayName("[AC-CM-114-1] 보이는 최상위 댓글을 부모로 지목하면 통과한다")
    void prepareCreate_validParent_passes() {
        postFound();
        commentFound(parent);

        writer.prepareCreate(POST_ID, 5L);
    }

    // ---------- create ----------

    @Test
    @DisplayName("[AC-CM-100-2, AC-CM-100-3] 최상위 댓글 작성: parent=null로 저장, 이미지는 순서대로, 글 commentCount +1")
    void create_topLevel_savesWithNullParentAndIncrementsCommentCount() {
        postFound();
        given(userAccountRepository.findById(AUTHOR_ID)).willReturn(Optional.of(author));
        given(commentRepository.save(any(CommunityComment.class))).willAnswer(inv -> {
            CommunityComment c = inv.getArgument(0);
            CommunityFixtures.set(c, "id", 70L);
            return c;
        });

        Long id = writer.create(AUTHOR_ID, POST_ID, null, " ㅊㅋ ", List.of("community/a.jpg", "community/b.jpg"));

        assertThat(id).isEqualTo(70L);
        ArgumentCaptor<CommunityComment> saved = ArgumentCaptor.forClass(CommunityComment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getParent()).isNull();
        assertThat(saved.getValue().isReply()).isFalse();
        assertThat(saved.getValue().getContent()).isEqualTo(" ㅊㅋ ");
        assertThat(saved.getValue().getUserAccount()).isSameAs(author);
        assertThat(saved.getValue().getPost()).isSameAs(post);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommunityCommentImage>> images = ArgumentCaptor.forClass(List.class);
        verify(commentImageRepository).saveAll(images.capture());
        assertThat(images.getValue()).extracting(CommunityCommentImage::getSortOrder).containsExactly(0, 1);
        verify(postRepository).adjustCommentCount(POST_ID, 1);
    }

    @Test
    @DisplayName("[AC-CM-114-1, AC-CM-114-2] 답글 작성: 부모가 연결되고 글 commentCount가 +1 된다")
    void create_reply_linksParentAndIncrementsCommentCount() {
        postFound();
        commentFound(parent);
        given(userAccountRepository.findById(AUTHOR_ID)).willReturn(Optional.of(author));
        given(commentRepository.save(any(CommunityComment.class))).willAnswer(inv -> inv.getArgument(0));

        writer.create(AUTHOR_ID, POST_ID, 5L, "ㄹㅇ", List.of());

        ArgumentCaptor<CommunityComment> saved = ArgumentCaptor.forClass(CommunityComment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getParent()).isSameAs(parent);
        assertThat(saved.getValue().isReply()).isTrue();
        verify(postRepository).adjustCommentCount(POST_ID, 1);
    }

    @Test
    @DisplayName("create: 트랜잭션 안에서도 부모 규칙을 다시 검사한다 - 그 사이 부모가 블라인드되면 저장 없이 410이다")
    void create_parentBlindedBetweenPrepareAndWrite_throwsAndSavesNothing() {
        parent.blind();
        postFound();
        commentFound(parent);

        assertThatThrownBy(() -> writer.create(AUTHOR_ID, POST_ID, 5L, "c", List.of()))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_BLINDED));
        verify(commentRepository, never()).save(any());
        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
    }

    // ---------- prepareUpdate ----------

    @Test
    @DisplayName("[AC-CM-111-1] 댓글이 없거나 삭제됐으면 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void prepareUpdate_commentMissing_throws404() {
        given(commentRepository.findByIdAndDeletedAtIsNull(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-111-2] 소속 글이 삭제됐으면 404 COMMUNITY_COMMENT_NOT_FOUND다(어느 쪽이 삭제됐는지 구분하지 않는다)")
    void prepareUpdate_postDeleted_throwsCommentNotFound() {
        post.delete(LocalDateTime.of(2026, 10, 2, 0, 0));
        commentFound(parent);

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-110-1] 작성자가 아니면 403 COMMUNITY_NOT_AUTHOR다 - 게시글 작성자라도 남의 댓글은 못 고친다")
    void prepareUpdate_notAuthor_throws403() {
        commentFound(CommunityFixtures.comment(5L, post, null, CommunityFixtures.account(OTHER_ID, "남")));

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
    }

    @Test
    @DisplayName("[AC-CM-110-1] 부모 댓글 작성자라도 남의 답글은 수정할 수 없다")
    void prepareUpdate_parentAuthorEditingOthersReply_throws403() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, CommunityFixtures.account(OTHER_ID, "남"));
        commentFound(reply);

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 6L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
    }

    @Test
    @DisplayName("[AC-CM-177-1] 소속 글이 블라인드면 작성자도 410 COMMUNITY_POST_BLINDED다")
    void prepareUpdate_postBlinded_throws410Post() {
        post.blind();
        commentFound(parent);

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
    }

    @Test
    @DisplayName("[AC-CM-181-2, AC-CM-216-2] 댓글이 블라인드면 작성자도 410 COMMUNITY_COMMENT_BLINDED다")
    void prepareUpdate_commentBlinded_throws410Comment() {
        parent.blind();
        commentFound(parent);

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_BLINDED));
    }

    @Test
    @DisplayName("prepareUpdate: 검증 순서는 작성자 403이 블라인드 410보다 앞이다(남의 블라인드 댓글은 410이 아니라 403)")
    void prepareUpdate_authorCheckPrecedesBlindedCheck() {
        CommunityComment others = CommunityFixtures.comment(5L, post, null, CommunityFixtures.account(OTHER_ID, "남"));
        others.blind();
        commentFound(others);

        assertThatThrownBy(() -> writer.prepareUpdate(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
    }

    @Test
    @DisplayName("prepareUpdate: 현재 붙은 확정 EP를 순서대로 돌려준다")
    void prepareUpdate_returnsCurrentEndpoints() {
        commentFound(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L))
                .willReturn(List.of(image(parent, "community/a.jpg", 0)));

        assertThat(writer.prepareUpdate(AUTHOR_ID, 5L)).containsExactly("community/a.jpg");
    }

    // ---------- update ----------

    @Test
    @DisplayName("[AC-CM-109-1, AC-CM-109-3] 최상위 댓글 수정: 본문·이미지 교체, 빠진 EP 반환, 응답은 assembler.assemble의 CommentResponse")
    void update_topLevel_replacesAndAssemblesWithReplies() {
        commentFound(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L))
                .willReturn(List.of(image(parent, "community/a.jpg", 0), image(parent, "community/b.jpg", 1)));
        CommentResponse assembled = org.mockito.Mockito.mock(CommentResponse.class);
        given(assembler.assemble(List.of(parent), AUTHOR_ID)).willReturn(List.of(assembled));

        UpdateResult result = writer.update(AUTHOR_ID, 5L, "수정", List.of("community/b.jpg"));

        assertThat(parent.getContent()).isEqualTo("수정");
        assertThat(result.removedEndpoints()).containsExactly("community/a.jpg");
        assertThat(result.item()).isSameAs(assembled);
        verify(commentImageRepository).deleteAllByCommentId(5L);
        verify(commentRepository).flush();
    }

    @Test
    @DisplayName("[AC-CM-217-1, AC-CM-218-1] 답글 수정: 응답은 assembler.assembleReply의 ReplyResponse(replies 키 없음)다")
    void update_reply_assemblesReplyResponse() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, author);
        commentFound(reply);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(6L)).willReturn(List.of());
        ReplyResponse assembled = org.mockito.Mockito.mock(ReplyResponse.class);
        given(assembler.assembleReply(reply, AUTHOR_ID)).willReturn(assembled);

        UpdateResult result = writer.update(AUTHOR_ID, 6L, "수정", List.of());

        assertThat(result.item()).isSameAs(assembled);
        verify(assembler, never()).assemble(any(), any());
    }

    @Test
    @DisplayName("[AC-CM-109-2] 수정은 카운터·작성자·소속 글을 바꾸지 않는다")
    void update_doesNotTouchCountersOrAuthor() {
        CommunityFixtures.set(parent, "likeCount", 3L);
        commentFound(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L)).willReturn(List.of());
        given(assembler.assemble(any(), any())).willReturn(List.of(org.mockito.Mockito.mock(CommentResponse.class)));

        writer.update(AUTHOR_ID, 5L, "수정", List.of());

        assertThat(parent.getLikeCount()).isEqualTo(3L);
        assertThat(parent.getUserAccount()).isSameAs(author);
        assertThat(parent.getPost()).isSameAs(post);
        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
    }

    // ---------- delete ----------

    @Test
    @DisplayName("[AC-CM-112-1, AC-CM-112-2] 삭제: 그 행만 deletedAt(Clock) 기록, 글 commentCount -1, 이미지 EP 반환")
    void delete_visibleComment_softDeletesDecrementsAndReturnsEndpoints() {
        commentLocked(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L))
                .willReturn(List.of(image(parent, "community/a.jpg", 0)));

        List<String> endpoints = writer.delete(AUTHOR_ID, 5L);

        assertThat(parent.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 12, 0, 0));
        verify(postRepository).adjustCommentCount(POST_ID, -1);
        assertThat(endpoints).containsExactly("community/a.jpg");
    }

    @Test
    @DisplayName("[AC-CM-112-5, AC-CM-210-1] 부모 삭제는 답글에 전파되지 않는다 - 부모 조회 외에 댓글 리포지토리를 부르지 않고 commentCount도 -1뿐이다")
    void delete_parent_doesNotPropagateToReplies() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, author);
        commentLocked(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 5L);

        assertThat(reply.getDeletedAt()).isNull();
        verify(commentRepository).findWithLockByIdAndDeletedAtIsNull(5L);
        verifyNoMoreInteractions(commentRepository);
        verify(postRepository).adjustCommentCount(POST_ID, -1);
    }

    @Test
    @DisplayName("[AC-CM-217-4] 답글 삭제는 commentCount -1이고 부모는 영향이 없다")
    void delete_reply_decrementsOnlyOnceAndLeavesParent() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, author);
        commentLocked(reply);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(6L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 6L);

        assertThat(reply.isDeleted()).isTrue();
        assertThat(parent.isDeleted()).isFalse();
        verify(postRepository).adjustCommentCount(POST_ID, -1);
    }

    @Test
    @DisplayName("[AC-CM-181-3] 블라인드 댓글도 작성자는 삭제할 수 있고, 신고 때 이미 뺐으니 commentCount를 다시 줄이지 않는다"
            + " — 신고가 락을 먼저 잡고 커밋한 뒤 삭제가 같은 락으로 읽는 경쟁 순서가 이 케이스다")
    void delete_blindedComment_allowedWithoutSecondDecrement() {
        parent.blind();
        commentLocked(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 5L);

        assertThat(parent.isDeleted()).isTrue();
        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-CM-177-2] 소속 글이 블라인드여도 댓글 작성자의 삭제는 막지 않는다")
    void delete_whenPostBlinded_stillAllowed() {
        post.blind();
        commentLocked(parent);
        given(commentImageRepository.findAllByComment_IdOrderBySortOrderAsc(5L)).willReturn(List.of());

        writer.delete(AUTHOR_ID, 5L);

        assertThat(parent.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("[AC-CM-110-1] 삭제: 작성자가 아니면 403이고 아무것도 바뀌지 않는다(게시글 작성자라도 마찬가지)")
    void delete_notAuthor_throws403() {
        commentLocked(parent);

        assertThatThrownBy(() -> writer.delete(OTHER_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_NOT_AUTHOR));
        assertThat(parent.isDeleted()).isFalse();
        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-CM-112-3] 이미 삭제된 댓글의 재삭제는 404 COMMUNITY_COMMENT_NOT_FOUND다"
            + " — 삭제가 먼저 커밋되면 뒤따르는 신고도 같은 락 쿼리에서 빈 결과를 받아 404 다")
    void delete_alreadyDeleted_throws404() {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> writer.delete(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-95-1] 소속 글이 삭제된 댓글의 삭제는 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void delete_postDeleted_throws404() {
        post.delete(LocalDateTime.of(2026, 10, 2, 0, 0));
        commentLocked(parent);

        assertThatThrownBy(() -> writer.delete(AUTHOR_ID, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        assertThat(parent.isDeleted()).isFalse();
    }
}
