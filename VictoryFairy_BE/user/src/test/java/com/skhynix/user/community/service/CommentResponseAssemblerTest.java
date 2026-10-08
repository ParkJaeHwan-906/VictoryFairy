package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentImage;
import com.skhynix.domain.community.entity.CommunityCommentReaction;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityCommentImageRepository;
import com.skhynix.domain.community.repository.CommunityCommentReactionRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.CommentStatus;
import com.skhynix.user.community.dto.ReplyResponse;
import com.skhynix.user.community.support.CommunityFixtures;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CommentResponseAssembler} - 최상위 댓글 N건 -> 응답 조립. 자리 표식 표현, 답글 묶기, 쿼리 3개 고정(N+1 없음).
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-106, 118~119, 212~215, 218.
 */
@ExtendWith(MockitoExtension.class)
class CommentResponseAssemblerTest {

    private static final Long ME = 10L;

    @Mock
    private CommunityCommentRepository commentRepository;
    @Mock
    private CommunityCommentImageRepository commentImageRepository;
    @Mock
    private CommunityCommentReactionRepository commentReactionRepository;

    private CommentResponseAssembler assembler;

    private final UserAccount me = CommunityFixtures.account(ME, "나");
    private final UserAccount other = CommunityFixtures.account(20L, "남");
    private CommunityPost post;

    @BeforeEach
    void setUp() {
        assembler = new CommentResponseAssembler(commentRepository, commentImageRepository,
                commentReactionRepository);
        post = CommunityFixtures.post(1L, CommunityFixtures.category(1L, "자유"), other);
    }

    private CommunityCommentImage image(CommunityComment c, String ep, int order) {
        return CommunityCommentImage.builder().comment(c).endpoint(ep).sortOrder(order).build();
    }

    private CommunityCommentReaction reaction(CommunityComment c, ReactionType type) {
        return CommunityCommentReaction.builder().comment(c).userAccount(me).type(type).build();
    }

    @Test
    @DisplayName("빈 목록이면 쿼리를 하나도 내지 않고 빈 리스트를 돌려준다")
    void assemble_empty_noQueries() {
        assertThat(assembler.assemble(List.of(), ME)).isEmpty();

        verifyNoInteractions(commentRepository, commentImageRepository, commentReactionRepository);
    }

    @Test
    @DisplayName("[AC-CM-106-1, AC-CM-106-3, AC-CM-106-4, AC-CM-118-2] 보이는 댓글: VISIBLE, parentCommentId=null, 본문·작성자·카운트가 실리고 답글이 없으면 replies=[] (null 아님)")
    void assemble_visibleComment_fullyPopulated() {
        CommunityComment c = CommunityFixtures.comment(5L, post, null, other);
        CommunityFixtures.set(c, "likeCount", 3L);
        CommunityFixtures.set(c, "dislikeCount", 1L);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of());
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(5L)))
                .willReturn(List.of(image(c, "community/a.jpg", 0), image(c, "community/b.jpg", 1)));
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(5L)))
                .willReturn(List.of(reaction(c, ReactionType.LIKE)));

        CommentResponse r = assembler.assemble(List.of(c), ME).get(0);

        assertThat(r.commentId()).isEqualTo(5L);
        assertThat(r.parentCommentId()).isNull();
        assertThat(r.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(r.content()).isEqualTo("댓글5");
        assertThat(r.imageUrls()).containsExactly("community/a.jpg", "community/b.jpg");
        assertThat(r.author().nickname()).isEqualTo("남");
        assertThat(r.likeCount()).isEqualTo(3L);
        assertThat(r.dislikeCount()).isEqualTo(1L);
        assertThat(r.myReaction()).isEqualTo(ReactionType.LIKE);
        assertThat(r.isAuthor()).isFalse();
        assertThat(r.createdAt()).isEqualTo(CommunityFixtures.CREATED_AT);
        assertThat(r.updatedAt()).isEqualTo(CommunityFixtures.CREATED_AT);
        assertThat(r.replies()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("[AC-CM-60-3 준용] 요청자 본인 댓글이면 isAuthor=true다")
    void assemble_ownComment_isAuthorTrue() {
        CommunityComment c = CommunityFixtures.comment(5L, post, null, me);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of());
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(5L))).willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(5L))).willReturn(List.of());

        CommentResponse r = assembler.assemble(List.of(c), ME).get(0);

        assertThat(r.isAuthor()).isTrue();
        assertThat(r.myReaction()).isNull();
        assertThat(r.imageUrls()).isEmpty();
    }

    @Test
    @DisplayName("[AC-CM-118-1, AC-CM-118-3] 답글은 부모별로 묶여 id 오름차순 그대로 replies에 실리고, ReplyResponse는 부모 id·VISIBLE을 갖는다")
    void assemble_repliesGroupedPerParentInOrder() {
        CommunityComment p1 = CommunityFixtures.comment(5L, post, null, other);
        CommunityComment p2 = CommunityFixtures.comment(8L, post, null, other);
        CommunityComment r6 = CommunityFixtures.comment(6L, post, p1, me);
        CommunityComment r7 = CommunityFixtures.comment(7L, post, p1, other);
        CommunityComment r9 = CommunityFixtures.comment(9L, post, p2, other);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L, 8L))).willReturn(List.of(r6, r7, r9));
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(5L, 8L, 6L, 7L, 9L)))
                .willReturn(List.of(image(r7, "community/r7.jpg", 0)));
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(5L, 8L, 6L, 7L, 9L)))
                .willReturn(List.of(reaction(r6, ReactionType.DISLIKE)));

        List<CommentResponse> result = assembler.assemble(List.of(p1, p2), ME);

        assertThat(result.get(0).replies()).extracting(ReplyResponse::commentId).containsExactly(6L, 7L);
        assertThat(result.get(1).replies()).extracting(ReplyResponse::commentId).containsExactly(9L);
        ReplyResponse first = result.get(0).replies().get(0);
        assertThat(first.parentCommentId()).isEqualTo(5L);
        assertThat(first.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(first.isAuthor()).isTrue();
        assertThat(first.myReaction()).isEqualTo(ReactionType.DISLIKE);
        assertThat(result.get(0).replies().get(1).imageUrls()).containsExactly("community/r7.jpg");
    }

    @Test
    @DisplayName("[AC-CM-212-1, AC-CM-212-2, AC-CM-212-4] 삭제된 부모 + 보이는 답글: DELETED 자리 표식 - 본문·이미지·작성자·카운트·내 반응·수정 시각이 비고 createdAt·replies만 산다(작성자 본인이 봐도 isAuthor=false)")
    void assemble_deletedParent_isPlaceholderEvenForItsAuthor() {
        CommunityComment p = CommunityFixtures.comment(5L, post, null, me);
        CommunityFixtures.set(p, "likeCount", 5L);
        CommunityFixtures.set(p, "dislikeCount", 2L);
        p.delete(LocalDateTime.of(2026, 10, 3, 0, 0));
        CommunityComment reply = CommunityFixtures.comment(6L, post, p, other);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of(reply));
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(6L))).willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(6L))).willReturn(List.of());

        CommentResponse r = assembler.assemble(List.of(p), ME).get(0);

        assertThat(r.commentId()).isEqualTo(5L);
        assertThat(r.parentCommentId()).isNull();
        assertThat(r.status()).isEqualTo(CommentStatus.DELETED);
        assertThat(r.content()).isNull();
        assertThat(r.imageUrls()).isEmpty();
        assertThat(r.author()).isNull();
        assertThat(r.likeCount()).isZero();
        assertThat(r.dislikeCount()).isZero();
        assertThat(r.myReaction()).isNull();
        assertThat(r.isAuthor()).isFalse();
        assertThat(r.updatedAt()).isNull();
        assertThat(r.createdAt()).isEqualTo(CommunityFixtures.CREATED_AT);
        assertThat(r.replies()).extracting(ReplyResponse::commentId).containsExactly(6L);
        // 저장값은 보존된다 - 0 고정은 표현일 뿐이다
        assertThat(p.getLikeCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[AC-CM-213-1, AC-CM-213-2] 블라인드된 부모 + 보이는 답글: BLINDED 자리 표식이며 작성자 본인에게도 동일하다")
    void assemble_blindedParent_isBlindedPlaceholder() {
        CommunityComment p = CommunityFixtures.comment(5L, post, null, me);
        p.blind();
        CommunityComment reply = CommunityFixtures.comment(6L, post, p, other);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of(reply));
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(6L))).willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(6L))).willReturn(List.of());

        CommentResponse r = assembler.assemble(List.of(p), ME).get(0);

        assertThat(r.status()).isEqualTo(CommentStatus.BLINDED);
        assertThat(r.content()).isNull();
        assertThat(r.author()).isNull();
        assertThat(r.isAuthor()).isFalse();
        assertThat(r.replies()).hasSize(1);
    }

    @Test
    @DisplayName("[AC-CM-215-1] 삭제와 블라인드가 겹친 부모의 자리 표식은 DELETED다(작성자의 치우기가 신고보다 우선)")
    void assemble_deletedAndBlindedParent_isDeleted() {
        CommunityComment p = CommunityFixtures.comment(5L, post, null, other);
        p.blind();
        p.delete(LocalDateTime.of(2026, 10, 3, 0, 0));
        CommunityComment reply = CommunityFixtures.comment(6L, post, p, other);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of(reply));
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(6L))).willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(6L))).willReturn(List.of());

        assertThat(assembler.assemble(List.of(p), ME).get(0).status()).isEqualTo(CommentStatus.DELETED);
    }

    @Test
    @DisplayName("[AC-CM-210-1, AC-CM-211-1] 자리 표식 부모의 답글은 부모와 무관하게 평소처럼 보이는 상태로 실린다")
    void assemble_repliesOfPlaceholderStayFullyVisible() {
        CommunityComment p = CommunityFixtures.comment(5L, post, null, other);
        p.delete(LocalDateTime.of(2026, 10, 3, 0, 0));
        CommunityComment reply = CommunityFixtures.comment(6L, post, p, me);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L))).willReturn(List.of(reply));
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(6L))).willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(6L))).willReturn(List.of());

        ReplyResponse r = assembler.assemble(List.of(p), ME).get(0).replies().get(0);

        assertThat(r.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(r.content()).isEqualTo("댓글6");
        assertThat(r.author().nickname()).isEqualTo("나");
        assertThat(r.isAuthor()).isTrue();
    }

    @Test
    @DisplayName("쿼리 3개로 닫는다 - 자리 표식 부모의 id는 이미지·반응 IN 목록에서 빠지고, 답글 조회는 1번뿐이다(N+1 방지)")
    void assemble_excludesPlaceholderIdsFromImageAndReactionLookups() {
        CommunityComment visible = CommunityFixtures.comment(5L, post, null, other);
        CommunityComment placeholder = CommunityFixtures.comment(8L, post, null, other);
        placeholder.delete(LocalDateTime.of(2026, 10, 3, 0, 0));
        CommunityComment reply = CommunityFixtures.comment(9L, post, placeholder, other);
        given(commentRepository.findVisibleRepliesByParentIds(List.of(5L, 8L))).willReturn(List.of(reply));
        ArgumentCaptor<Collection<Long>> imageIds = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<Long>> reactionIds = ArgumentCaptor.forClass(Collection.class);
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(imageIds.capture()))
                .willReturn(List.of());
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(any(), reactionIds.capture()))
                .willReturn(List.of());

        assembler.assemble(List.of(visible, placeholder), ME);

        assertThat(imageIds.getValue()).containsExactlyInAnyOrder(5L, 9L);
        assertThat(reactionIds.getValue()).containsExactlyInAnyOrder(5L, 9L);
        verify(commentRepository, times(1)).findVisibleRepliesByParentIds(anyCollection());
        verify(commentImageRepository, times(1)).findAllByComment_IdInOrderBySortOrderAsc(anyCollection());
        verify(commentReactionRepository, times(1)).findAllByUserAccount_IdAndComment_IdIn(any(), anyCollection());
    }

    @Test
    @DisplayName("모든 부모가 자리 표식이고 답글도 없으면 이미지·반응 쿼리를 내지 않는다")
    void assemble_nothingVisible_skipsImageAndReactionQueries() {
        CommunityComment placeholder = CommunityFixtures.comment(8L, post, null, other);
        placeholder.delete(LocalDateTime.of(2026, 10, 3, 0, 0));
        given(commentRepository.findVisibleRepliesByParentIds(List.of(8L))).willReturn(List.of());

        assembler.assemble(List.of(placeholder), ME);

        verifyNoInteractions(commentImageRepository, commentReactionRepository);
    }

    @Test
    @DisplayName("[AC-CM-218-1] assembleReply: 답글 한 건의 응답(부모 id, VISIBLE)을 이미지·반응 쿼리 2개로 만든다")
    void assembleReply_buildsSingleReply() {
        CommunityComment parent = CommunityFixtures.comment(5L, post, null, other);
        CommunityComment reply = CommunityFixtures.comment(6L, post, parent, me);
        given(commentImageRepository.findAllByComment_IdInOrderBySortOrderAsc(List.of(6L)))
                .willReturn(List.of(image(reply, "community/r.jpg", 0)));
        given(commentReactionRepository.findAllByUserAccount_IdAndComment_IdIn(ME, List.of(6L)))
                .willReturn(List.of(reaction(reply, ReactionType.LIKE)));

        ReplyResponse r = assembler.assembleReply(reply, ME);

        assertThat(r.commentId()).isEqualTo(6L);
        assertThat(r.parentCommentId()).isEqualTo(5L);
        assertThat(r.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(r.imageUrls()).containsExactly("community/r.jpg");
        assertThat(r.myReaction()).isEqualTo(ReactionType.LIKE);
        assertThat(r.isAuthor()).isTrue();
        verify(commentRepository, never()).findVisibleRepliesByParentIds(anyCollection());
        verify(commentRepository, never()).findById(anyLong());
    }
}
