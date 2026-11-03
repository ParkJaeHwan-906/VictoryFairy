package com.skhynix.user.community.service;

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
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 1건 = 즉시 블라인드(임계치·관리자 승인 없음, 채팅과 동일). 채팅과 다른 점은 신고 행을 남긴다는 것 —
 * 신고 1건으로 숨기는 정책에서 악의적 신고를 가려낼 유일한 근거다. 해제 경로는 없다(DB 직접 UPDATE).
 *
 * <p>이미 블라인드된 대상의 재신고는 200 no-op 이고 같은 신고자의 재신고는 행을 더하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityReportService {

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityReportRepository reportRepository;
    private final UserAccountRepository userAccountRepository;
    // 신고 행의 created_at 출처 — 커뮤니티 엔티티는 Hibernate 생성값(컨테이너 UTC)을 쓰지 않는다
    private final Clock clock;

    // 404 → 자기 글 403 → 블라인드(이미면 no-op)
    public void reportPost(Long userAccountId, Long postId) {
        CommunityPost post = postRepository.findByIdAndDeletedAtIsNull(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));
        if (post.getUserAccount().getId().equals(userAccountId)) {
            throw new BusinessException(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED);
        }
        if (!post.isBlinded()) {
            post.blind();
        }
        recordReport(userAccountId, ReportTargetType.POST, postId);
    }

    /**
     * 댓글·답글 공용. 그 행만 숨기고 답글에는 전파하지 않는다 — 전파하면 댓글 하나를 신고해 그 아래 답글
     * 전부를 지우는 공격이 된다. 보이던 행이었을 때만 글의 {@code commentCount} 를 1 줄인다.
     * 행을 잠그는 것은 동시 신고 2건이 둘 다 "보이던 행"을 보고 두 번 빼는 것을 막기 위해서다.
     */
    // 댓글 404 → 글 404 → 글 410 → 자기 댓글 403 → 블라인드(이미면 no-op)
    public void reportComment(Long userAccountId, Long commentId) {
        CommunityComment comment = commentRepository.findWithLockByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND));
        if (comment.getPost().isDeleted()) {
            throw new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND);
        }
        if (comment.getPost().isBlinded()) {
            throw new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED);
        }
        if (comment.getUserAccount().getId().equals(userAccountId)) {
            throw new BusinessException(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED);
        }
        if (!comment.isBlinded()) {
            comment.blind();
            postRepository.adjustCommentCount(comment.getPost().getId(), -1);
        }
        recordReport(userAccountId, ReportTargetType.COMMENT, commentId);
    }

    private void recordReport(Long userAccountId, ReportTargetType targetType, long targetId) {
        if (reportRepository.existsByReporter_IdAndTargetTypeAndTargetId(userAccountId, targetType,
                targetId)) {
            return;
        }
        UserAccount reporter = userAccountRepository.findById(userAccountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        reportRepository.save(CommunityReport.builder()
                .reporter(reporter)
                .targetType(targetType)
                .targetId(targetId)
                .createdAt(LocalDateTime.now(clock))
                .build());
    }
}
