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
import com.skhynix.domain.community.entity.CommunityPost;
import com.skhynix.domain.community.entity.CommunityReport;
import com.skhynix.domain.community.entity.ReportTargetType;
import com.skhynix.domain.community.repository.CommunityCommentRepository;
import com.skhynix.domain.community.repository.CommunityPostRepository;
import com.skhynix.domain.community.repository.CommunityReportRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
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
 * {@link CommunityReportService} - 신고 1건 = 즉시 블라인드, 자기 신고 403, 멱등, 댓글 블라인드 시 commentCount.
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-170~183, 211, 216~217.
 */
@ExtendWith(MockitoExtension.class)
class CommunityReportServiceTest {

    private static final Long REPORTER = 20L;
    private static final Long AUTHOR = 10L;

    @Mock
    private CommunityPostRepository postRepository;
    @Mock
    private CommunityCommentRepository commentRepository;
    @Mock
    private CommunityReportRepository reportRepository;
    @Mock
    private UserAccountRepository userAccountRepository;

    private CommunityReportService service;

    private final UserAccount reporter = CommunityFixtures.account(REPORTER, "신고자");
    private final UserAccount author = CommunityFixtures.account(AUTHOR, "작성자");
    private CommunityPost post;
    private CommunityComment comment;

    @BeforeEach
    void setUp() {
        service = new CommunityReportService(postRepository, commentRepository, reportRepository,
                userAccountRepository,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T03:00:00Z"),
                        java.time.ZoneId.of("Asia/Seoul")));
        post = CommunityFixtures.post(1L, CommunityFixtures.category(1L, "자유"), author);
        comment = CommunityFixtures.comment(5L, post, null, author);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ---------- 게시글 ----------

    @Test
    @DisplayName("[AC-CM-170-1, AC-CM-175-1] 타인 글 신고는 즉시 blinded=true로 바꾸고 (신고자, POST, 글 id) 신고 행을 남긴다")
    void reportPost_blindsImmediatelyAndRecordsReport() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 1L))
                .willReturn(false);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.of(reporter));

        service.reportPost(REPORTER, 1L);

        assertThat(post.isBlinded()).isTrue();
        ArgumentCaptor<CommunityReport> saved = ArgumentCaptor.forClass(CommunityReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getReporter()).isSameAs(reporter);
        assertThat(saved.getValue().getTargetType()).isEqualTo(ReportTargetType.POST);
        assertThat(saved.getValue().getTargetId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("[AC-CM-171-1] 자기 글 신고는 403 COMMUNITY_SELF_REPORT_NOT_ALLOWED이고 블라인드도 신고 행도 없다")
    void reportPost_selfReport_throws403WithoutSideEffects() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));

        assertThatThrownBy(() -> service.reportPost(AUTHOR, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED));
        assertThat(post.isBlinded()).isFalse();
        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("[AC-CM-172-1] 이미 블라인드된 글의 재신고는 200(예외 없음)이고 상태는 그대로 blinded다")
    void reportPost_alreadyBlinded_isIdempotent() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 1L))
                .willReturn(true);

        service.reportPost(REPORTER, 1L);

        assertThat(post.isBlinded()).isTrue();
    }

    @Test
    @DisplayName("[AC-CM-175-2] 같은 신고자의 같은 대상 재신고는 신고 행을 추가하지 않는다")
    void reportPost_sameReporterAgain_doesNotAddRow() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 1L))
                .willReturn(true);

        service.reportPost(REPORTER, 1L);

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("[AC-CM-175-3, AC-CM-172-1] 이미 블라인드된 글을 다른 신고자가 신고하면 상태는 그대로이고 신고 행은 추가된다")
    void reportPost_differentReporterOnBlindedPost_addsRow() {
        post.blind();
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 1L))
                .willReturn(false);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.of(reporter));

        service.reportPost(REPORTER, 1L);

        verify(reportRepository).save(any(CommunityReport.class));
    }

    @Test
    @DisplayName("[AC-CM-173-1] 없거나 삭제된 글 신고는 404 COMMUNITY_POST_NOT_FOUND다")
    void reportPost_notFound_throws404() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportPost(REPORTER, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("신고자 계정이 사라졌으면 신고 행 저장 대신 401 UNAUTHENTICATED다")
    void reportPost_reporterAccountMissing_throwsUnauthenticated() {
        given(postRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(post));
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.POST, 1L))
                .willReturn(false);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportPost(REPORTER, 1L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    // ---------- 댓글·답글 ----------

    private void commentLocked(CommunityComment target) {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
    }

    @Test
    @DisplayName("[AC-CM-180-1] 타인 댓글 신고는 그 행만 blinded로 바꾸고 소속 글 commentCount를 1 줄이며 COMMENT 신고 행을 남긴다")
    void reportComment_blindsRowAndDecrementsPostCommentCount() {
        commentLocked(comment);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.of(reporter));

        service.reportComment(REPORTER, 5L);

        assertThat(comment.isBlinded()).isTrue();
        verify(postRepository).adjustCommentCount(1L, -1);
        ArgumentCaptor<CommunityReport> saved = ArgumentCaptor.forClass(CommunityReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getTargetType()).isEqualTo(ReportTargetType.COMMENT);
        assertThat(saved.getValue().getTargetId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[AC-CM-180-2] 이미 블라인드된 댓글의 재신고는 commentCount를 더 줄이지 않는다")
    void reportComment_alreadyBlinded_doesNotDecrementAgain() {
        comment.blind();
        commentLocked(comment);
        given(reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(REPORTER, ReportTargetType.COMMENT, 5L))
                .willReturn(true);

        service.reportComment(REPORTER, 5L);

        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[AC-CM-180-3, AC-CM-211-1, AC-CM-217-3] 답글을 신고하면 그 답글만 블라인드되고 부모는 건드리지 않으며 commentCount는 1만 준다")
    void reportComment_onReply_blindsOnlyReply() {
        CommunityComment reply = CommunityFixtures.comment(6L, post, comment, author);
        commentLocked(reply);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.of(reporter));

        service.reportComment(REPORTER, 6L);

        assertThat(reply.isBlinded()).isTrue();
        assertThat(comment.isBlinded()).isFalse();
        verify(postRepository).adjustCommentCount(1L, -1);
    }

    @Test
    @DisplayName("[AC-CM-180-3, AC-CM-211-1] 부모 댓글 신고는 자식 쿼리를 내지 않는다 - 락 조회 외의 댓글 리포지토리 호출이 없다(전파 없음)")
    void reportComment_onParent_doesNotPropagateToChildren() {
        commentLocked(comment);
        given(userAccountRepository.findById(REPORTER)).willReturn(Optional.of(reporter));

        service.reportComment(REPORTER, 5L);

        verify(commentRepository).findWithLockByIdAndDeletedAtIsNull(5L);
        verifyNoMoreInteractions(commentRepository);
    }

    @Test
    @DisplayName("[AC-CM-182-1] 자기 댓글 신고는 403 COMMUNITY_SELF_REPORT_NOT_ALLOWED이고 블라인드·카운트·신고 행이 없다")
    void reportComment_selfReport_throws403() {
        commentLocked(comment);

        assertThatThrownBy(() -> service.reportComment(AUTHOR, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED));
        assertThat(comment.isBlinded()).isFalse();
        verify(postRepository, never()).adjustCommentCount(anyLong(), anyLong());
        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("[AC-CM-182-2, AC-CM-216-1] 삭제된 댓글(자리 표식 포함)은 락 쿼리에서 빠져 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void reportComment_deletedComment_throws404() {
        given(commentRepository.findWithLockByIdAndDeletedAtIsNull(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportComment(REPORTER, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-111-2] 소속 글이 삭제됐으면 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void reportComment_postDeleted_throwsCommentNotFound() {
        post.delete(LocalDateTime.of(2026, 10, 2, 0, 0));
        commentLocked(comment);

        assertThatThrownBy(() -> service.reportComment(REPORTER, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("[AC-CM-177-1] 소속 글이 블라인드면 댓글 신고도 410 COMMUNITY_POST_BLINDED다")
    void reportComment_postBlinded_throws410() {
        post.blind();
        commentLocked(comment);

        assertThatThrownBy(() -> service.reportComment(REPORTER, 5L))
                .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ErrorCode.COMMUNITY_POST_BLINDED));
        verifyNoInteractions(reportRepository);
    }
}
