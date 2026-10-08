package com.skhynix.user.community.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.community.dto.CommentItemResponse;
import com.skhynix.user.community.dto.CommentUpdateRequest;
import com.skhynix.user.community.dto.ReactionRequest;
import com.skhynix.user.community.dto.ReactionResponse;
import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityReactionService;
import com.skhynix.user.community.service.CommunityReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 댓글·답글 공용 자원 경로. 작성·목록은 글 아래({@code /community/posts/{postId}/comments})에 있다. */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/community/comments/**
@RequestMapping("/community/comments")
public class CommunityCommentController {

    private final CommunityCommentCommandService commentCommandService;
    private final CommunityReactionService reactionService;
    private final CommunityReportService reportService;

    // 최상위 댓글이면 CommentResponse(replies 포함), 답글이면 ReplyResponse(replies 키 없음)
    @PutMapping("/{commentId}")
    public ResponseEntity<ApiResponse<CommentItemResponse>> updateComment(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long commentId,
            @Valid @RequestBody CommentUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                commentCommandService.update(userAccountId, commentId, request)));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long commentId) {
        commentCommandService.delete(userAccountId, commentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{commentId}/reaction")
    public ResponseEntity<ApiResponse<ReactionResponse>> reactToComment(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long commentId,
            @Valid @RequestBody ReactionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                reactionService.reactToComment(userAccountId, commentId, request.reaction())));
    }

    @PostMapping("/{commentId}/report")
    public ResponseEntity<ApiResponse<Void>> reportComment(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long commentId) {
        reportService.reportComment(userAccountId, commentId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
