package com.skhynix.user.community.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.user.community.dto.CommentIdResponse;
import com.skhynix.user.community.dto.CommentRequest;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.MyPostSummaryResponse;
import com.skhynix.user.community.dto.PageResponse;
import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostIdResponse;
import com.skhynix.user.community.dto.PostRequest;
import com.skhynix.user.community.dto.PostSort;
import com.skhynix.user.community.dto.PostSummaryResponse;
import com.skhynix.user.community.dto.ReactionRequest;
import com.skhynix.user.community.dto.ReactionResponse;
import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityCommentQueryService;
import com.skhynix.user.community.service.CommunityPostCommandService;
import com.skhynix.user.community.service.CommunityPostQueryService;
import com.skhynix.user.community.service.CommunityReactionService;
import com.skhynix.user.community.service.CommunityReportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 게시글 경로 + 글 아래 댓글 경로. 요청자는 토큰에서만 식별한다 — 본문·경로·쿼리 어디에도 계정 식별자를
 * 받는 자리가 없다. 전 경로 인증 필수({@code SecurityConfig} 무수정이 정답).
 *
 * <p>{@code /popular}·{@code /me} 는 {@code /{postId}} 보다 구체적인 리터럴 매핑이라 {@code postId} 파싱에
 * 걸리지 않는다.
 *
 * <p>페이징 파라미터의 {@code @Min/@Max} 는 스프링 내장 메서드 검증이 받는다 — ⚠ 클래스에 {@code @Validated}
 * 를 붙이지 말 것(AOP 검증으로 바뀌어 {@code ConstraintViolationException} = 500 이 된다).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /api 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /api/community/posts/**
@RequestMapping("/community/posts")
public class CommunityPostController {

    private final CommunityPostQueryService postQueryService;
    private final CommunityPostCommandService postCommandService;
    private final CommunityCommentQueryService commentQueryService;
    private final CommunityCommentCommandService commentCommandService;
    private final CommunityReactionService reactionService;
    private final CommunityReportService reportService;

    @PostMapping
    public ResponseEntity<ApiResponse<PostIdResponse>> createPost(
            @AuthenticationPrincipal Long userAccountId,
            @Valid @RequestBody PostRequest request) {
        Long postId = postCommandService.create(userAccountId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new PostIdResponse(postId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PostSummaryResponse>>> getPosts(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "latest") PostSort sort,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50 이하여야 합니다.") int size) {
        return ResponseEntity.ok(ApiResponse.ok(postQueryService.getPosts(categoryId, sort, page, size)));
    }

    // 페이징하지 않는다(최대 5건 배열). ?page= 는 무시된다.
    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<List<PostSummaryResponse>>> getPopularPosts(
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(ApiResponse.ok(postQueryService.getPopularPosts(categoryId)));
    }

    // 최신순뿐이라 sort 파라미터가 없다(붙여도 무시)
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<PageResponse<MyPostSummaryResponse>>> getMyPosts(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50 이하여야 합니다.") int size) {
        return ResponseEntity.ok(ApiResponse.ok(postQueryService.getMyPosts(userAccountId, page, size)));
    }

    // 조회수 증가 부수효과가 있는 GET — 이 모듈에서 읽기 전용이 아닌 유일한 GET 이다
    @GetMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPost(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId) {
        return ResponseEntity.ok(ApiResponse.ok(postQueryService.getPost(userAccountId, postId)));
    }

    // PUT 전체 교체 — 부분 생략 불가(title 키 없음 → 400)
    @PutMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostDetailResponse>> updatePost(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId,
            @Valid @RequestBody PostRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(postCommandService.update(userAccountId, postId, request)));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId) {
        postCommandService.delete(userAccountId, postId);
        return ResponseEntity.noContent().build();
    }

    // 상태 지정(멱등) — 토글이 아니다. 같은 값을 다시 보내면 카운트가 안 변한다.
    @PutMapping("/{postId}/reaction")
    public ResponseEntity<ApiResponse<ReactionResponse>> reactToPost(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId,
            @Valid @RequestBody ReactionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                reactionService.reactToPost(userAccountId, postId, request.reaction())));
    }

    // 본문 없음(사유 없음). 보내도 무시된다.
    @PostMapping("/{postId}/report")
    public ResponseEntity<ApiResponse<Void>> reportPost(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId) {
        reportService.reportPost(userAccountId, postId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // parentCommentId 가 있으면 답글 — 답글 전용 경로는 없다
    @PostMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentIdResponse>> createComment(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId,
            @Valid @RequestBody CommentRequest request) {
        Long commentId = commentCommandService.create(userAccountId, postId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(new CommentIdResponse(commentId)));
    }

    @GetMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<PageResponse<CommentResponse>>> getComments(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long postId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size는 1 이상이어야 합니다.")
            @Max(value = 50, message = "size는 50 이하여야 합니다.") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                commentQueryService.getComments(userAccountId, postId, page, size)));
    }
}
