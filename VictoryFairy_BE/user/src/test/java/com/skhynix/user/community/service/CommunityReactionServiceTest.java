package com.skhynix.user.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.CommunityComment;
import com.skhynix.domain.community.entity.CommunityCommentReaction;
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityPostReaction;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.domain.community.repository.CommunityCommentReactionRepository;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostReactionRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.community.dto.ReactionChoice;
import com.skhynix.user.community.dto.ReactionResponse;
import com.skhynix.user.community.support.CommunityFixtures;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CommunityReactionService} - 3상태 반응 전이와 카운터. 요구사항: {@code docs/requirements/user/community.md}
 * USER-CM-120~132, 128~129. 행 잠금 하에서 호출된다는 전제(락 쿼리 사용)도 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class CommunityReactionServiceTest {

    private static final Long ME = 20L;
    private static final Long AUTHOR = 10L;

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityPostReactionRepository postReactionRepository;
    @Mock
    private CommunityCommentRepository commentRepository;
    @Mock
    private CommunityCommentReactionRepository commentReactionRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private CommunityReactionService service;

    private final UserAccount me = CommunityFixtures.account(ME, "나");
    private final UserAccount author = CommunityFixtures.account(AUTHOR, "작성자");
    private CommunityPost post;
    private CommunityComment comment;

    @BeforeEach
    void setUp() {
        service = new CommunityReactionService(postRepository, postReactionRepository, commentRepository,
                commentReactionRepository, userAccountRepository,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T03:00:00Z"),
                        java.time.ZoneId.of("Asia/Seoul")));
        post = CommunityFixtures.post(1L, CommunityFixtures.category(1L, "자유"), author);
        comment = CommunityFixtures.comment(5L, post, null, author);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    private void postLocked() {
        given(postRepository.findWithLockByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
    }

    private void commentLocked() {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(5L)).willReturn(Optional.of(comment));
    }

    private CommunityPostReaction existingPost(ReactionType type) {
        return CommunityPostReaction.builder().userAccount(me).post(post).type(type).build();
    }

    // ---------- 게시글: 상태 전이 ----------

    @Test
    @DisplayName("[AC-CM-120-1, AC-CM-120-2] 반응이 없던 상태에서 LIKE: 반응 행 1건 저장, likeCount 1, 응답 myReaction=LIKE")
    void reactToPost_noneToLike_savesRowAndIncrementsLike() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());
        given(userAccountRepository.findById(ME)).willReturn(Optional.of(me));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.LIKE);

        ArgumentCaptor<CommunityPostReaction> saved = ArgumentCaptor.forClass(CommunityPostReaction.class);
        verify(postReactionRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(ReactionType.LIKE);
        assertThat(saved.getValue().getUserAccount()).isSameAs(me);
        assertThat(saved.getValue().getPost()).isSameAs(post);
        assertThat(response).isEqualTo(new ReactionResponse(ReactionType.LIKE, 1, 0));
    }

    @Test
    @DisplayName("[AC-CM-121-1] 반응이 없던 상태에서 DISLIKE: dislikeCount 1, likeCount 0")
    void reactToPost_noneToDislike() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());
        given(userAccountRepository.findById(ME)).willReturn(Optional.of(me));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.DISLIKE);

        assertThat(response).isEqualTo(new ReactionResponse(ReactionType.DISLIKE, 0, 1));
    }

    @Test
    @DisplayName("[AC-CM-122-1] LIKE 상태에서 NONE: 반응 행을 삭제하고 likeCount가 줄며 myReaction=null이다")
    void reactToPost_likeToNone_deletesRow() {
        CommunityPostReaction existing = existingPost(ReactionType.LIKE);
        CommunityFixtures.set(post, "likeCount", 1L);
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.of(existing));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.NONE);

        verify(postReactionRepository).delete(existing);
        assertThat(response).isEqualTo(new ReactionResponse(null, 0, 0));
    }

    @Test
    @DisplayName("[AC-CM-122-2] 반응이 없던 상태에서 NONE: 200 + 변화 없음(저장·삭제 호출 없음)")
    void reactToPost_noneToNone_noChange() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.NONE);

        assertThat(response).isEqualTo(new ReactionResponse(null, 0, 0));
        verify(postReactionRepository, never()).save(any());
        verify(postReactionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[AC-CM-123-1] LIKE에서 DISLIKE: 같은 행의 type만 바뀌고(저장·삭제 없음) like -1, dislike +1")
    void reactToPost_likeToDislike_changesTypeOfSameRow() {
        CommunityPostReaction existing = existingPost(ReactionType.LIKE);
        CommunityFixtures.set(post, "likeCount", 1L);
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.of(existing));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.DISLIKE);

        assertThat(existing.getType()).isEqualTo(ReactionType.DISLIKE);
        assertThat(response).isEqualTo(new ReactionResponse(ReactionType.DISLIKE, 0, 1));
        verify(postReactionRepository, never()).save(any());
        verify(postReactionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("DISLIKE에서 LIKE: dislike -1, like +1 (반대 방향 전이)")
    void reactToPost_dislikeToLike() {
        CommunityPostReaction existing = existingPost(ReactionType.DISLIKE);
        CommunityFixtures.set(post, "dislikeCount", 1L);
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.of(existing));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.LIKE);

        assertThat(existing.getType()).isEqualTo(ReactionType.LIKE);
        assertThat(response).isEqualTo(new ReactionResponse(ReactionType.LIKE, 1, 0));
    }

    @Test
    @DisplayName("[AC-CM-124-1, AC-CM-124-2] 같은 값을 10번 보내도 likeCount는 1이다(멱등 - 거절이 아니라 무변화 200)")
    void reactToPost_sameValueRepeated_staysIdempotent() {
        postLocked();
        given(userAccountRepository.findById(ME)).willReturn(Optional.of(me));
        ArgumentCaptor<CommunityPostReaction> saved = ArgumentCaptor.forClass(CommunityPostReaction.class);
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());

        ReactionResponse last = service.reactToPost(ME, 1L, ReactionChoice.LIKE);
        verify(postReactionRepository).save(saved.capture());
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L))
                .willReturn(Optional.of(saved.getValue()));
        for (int i = 0; i < 9; i++) {
            last = service.reactToPost(ME, 1L, ReactionChoice.LIKE);
        }

        assertThat(last).isEqualTo(new ReactionResponse(ReactionType.LIKE, 1, 0));
        assertThat(post.getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("카운터가 이미 0인데(불일치 상태) 반응을 취소해도 음수로 내려가지 않는다")
    void reactToPost_cancelWhenCounterAlreadyZero_staysZero() {
        CommunityPostReaction existing = existingPost(ReactionType.LIKE);
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.of(existing));

        ReactionResponse response = service.reactToPost(ME, 1L, ReactionChoice.NONE);

        assertThat(response.likeCount()).isZero();
    }

    @Test
    @DisplayName("[AC-CM-128-1] 작성자가 자기 글에 LIKE를 눌러도 허용된다")
    void reactToPost_byAuthor_isAllowed() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(AUTHOR, 1L)).willReturn(Optional.empty());
        given(userAccountRepository.findById(AUTHOR)).willReturn(Optional.of(author));

        ReactionResponse response = service.reactToPost(AUTHOR, 1L, ReactionChoice.LIKE);

        assertThat(response.likeCount()).isEqualTo(1);
    }

    // ---------- 게시글: 거절 ----------

    @Test
    @DisplayName("[AC-CM-127-1] 없거나 삭제된 글은 404 COMMUNITY_POST_NOT_FOUND이고 반응 저장소를 건드리지 않는다")
    void reactToPost_notFound_throws404() {
        given(postRepository.findWithLockByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactToPost(ME, 1L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        verifyNoInteractions(postReactionRepository);
    }

    @Test
    @DisplayName("[AC-CM-127-2] 블라인드 글은 410 COMMUNITY_POST_BLINDED이고 기존 반응 행은 불변이다")
    void reactToPost_blinded_throws410AndKeepsReaction() {
        post.blind();
        postLocked();

        assertThatThrownBy(() -> service.reactToPost(ME, 1L, ReactionChoice.NONE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        verifyNoInteractions(postReactionRepository);
    }

    @Test
    @DisplayName("[AC-CM-131-1] 대상 글은 일반 findById가 아니라 비관적 락 쿼리로만 읽는다(동시 반응 직렬화의 전제)")
    void reactToPost_readsPostOnlyThroughLockingQuery() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());

        service.reactToPost(ME, 1L, ReactionChoice.NONE);

        verify(postRepository).findWithLockByIdAndDeletedAtIsNull(1L);
        verify(postRepository, never()).findById(any());
        verify(postRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("요청자 계정이 사라졌으면(필터 통과 후 삭제) 반응 저장 대신 401 UNAUTHENTICATED다")
    void reactToPost_accountMissing_throwsUnauthenticated() {
        postLocked();
        given(postReactionRepository.findByUserAccount_IdAndPost_Id(ME, 1L)).willReturn(Optional.empty());
        given(userAccountRepository.findById(ME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactToPost(ME, 1L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verify(postReactionRepository, never()).save(any());
    }

    // ---------- 댓글·답글 ----------

    @Test
    @DisplayName("[AC-CM-129-1] 댓글 LIKE -> DISLIKE -> NONE 왕복이 게시글과 같은 응답 형태·카운트로 이어진다")
    void reactToComment_roundTrip() {
        commentLocked();
        given(userAccountRepository.findById(ME)).willReturn(Optional.of(me));
        given(commentReactionRepository.findByUserAccount_IdAndComment_Id(ME, 5L)).willReturn(Optional.empty());
        ReactionResponse liked = service.reactToComment(ME, 5L, ReactionChoice.LIKE);
        ArgumentCaptor<CommunityCommentReaction> saved = ArgumentCaptor.forClass(CommunityCommentReaction.class);
        verify(commentReactionRepository).save(saved.capture());
        CommunityCommentReaction row = saved.getValue();
        given(commentReactionRepository.findByUserAccount_IdAndComment_Id(ME, 5L)).willReturn(Optional.of(row));

        ReactionResponse disliked = service.reactToComment(ME, 5L, ReactionChoice.DISLIKE);
        ReactionResponse none = service.reactToComment(ME, 5L, ReactionChoice.NONE);

        assertThat(liked).isEqualTo(new ReactionResponse(ReactionType.LIKE, 1, 0));
        assertThat(disliked).isEqualTo(new ReactionResponse(ReactionType.DISLIKE, 0, 1));
        assertThat(none).isEqualTo(new ReactionResponse(null, 0, 0));
        verify(commentReactionRepository).delete(row);
    }

    @Test
    @DisplayName("[AC-CM-217-2] 답글(부모가 있는 행)에도 같은 반응 규칙이 적용된다")
    void reactToComment_onReply_sameRules() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, comment, author);
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(6L)).willReturn(Optional.of(reply));
        given(commentReactionRepository.findByUserAccount_IdAndComment_Id(ME, 6L)).willReturn(Optional.empty());
        given(userAccountRepository.findById(ME)).willReturn(Optional.of(me));

        ReactionResponse response = service.reactToComment(ME, 6L, ReactionChoice.LIKE);

        assertThat(response).isEqualTo(new ReactionResponse(ReactionType.LIKE, 1, 0));
    }

    @Test
    @DisplayName("[AC-CM-129-2] 없거나 삭제된 댓글은 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void reactToComment_notFound_throws404() {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactToComment(ME, 5L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        verifyNoInteractions(commentReactionRepository);
    }

    @Test
    @DisplayName("[AC-CM-111-2] 소속 글이 삭제됐으면 댓글이 살아 있어도 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void reactToComment_postDeleted_throwsCommentNotFound() {
        post.delete(LocalDateTime.of(2026, 10, 2, 0, 0));
        commentLocked();

        assertThatThrownBy(() -> service.reactToComment(ME, 5L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-177-1] 소속 글이 블라인드면 410 COMMUNITY_POST_BLINDED(글 사유)다")
    void reactToComment_postBlinded_throwsPostBlinded() {
        post.blind();
        commentLocked();

        assertThatThrownBy(() -> service.reactToComment(ME, 5L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
    }

    @Test
    @DisplayName("[AC-CM-129-2, AC-CM-181-2] 블라인드 댓글은 410 COMMUNITY_COMMENT_BLINDED다")
    void reactToComment_commentBlinded_throwsCommentBlinded() {
        comment.blind();
        commentLocked();

        assertThatThrownBy(() -> service.reactToComment(ME, 5L, ReactionChoice.LIKE))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_BLINDED));
        verifyNoInteractions(commentReactionRepository);
    }
}
