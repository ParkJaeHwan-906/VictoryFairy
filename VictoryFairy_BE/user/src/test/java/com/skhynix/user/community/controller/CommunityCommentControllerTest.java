package com.skhynix.user.community.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.community.entity.ReactionType;
import com.skhynix.user.community.dto.AuthorResponse;
import com.skhynix.user.community.dto.CommentResponse;
import com.skhynix.user.community.dto.CommentStatus;
import com.skhynix.user.community.dto.CommentUpdateRequest;
import com.skhynix.user.community.dto.ReactionChoice;
import com.skhynix.user.community.dto.ReactionResponse;
import com.skhynix.user.community.dto.ReplyResponse;
import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityReactionService;
import com.skhynix.user.community.service.CommunityReportService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link CommunityCommentController} 슬라이스 - 댓글·답글 공용 4개 엔드포인트. 최상위 댓글 수정 응답(CommentResponse,
 * replies 포함)과 답글 수정 응답(ReplyResponse, replies 키 없음)이 직렬화에서 갈리는지, 오류 코드 매핑을 본다.
 * 요구사항: {@code docs/requirements/user/community.md} USER-CM-109~112, 129, 180~182, 217~218.
 */
@WebMvcTest(CommunityCommentController.class)
@ContextConfiguration(classes = CommunityCommentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CommunityCommentControllerTest extends CommunitySliceSupport {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
    private static final String[] REPLY_KEYS = {"commentId", "parentCommentId", "status", "content", "imageUrls",
            "author", "likeCount", "dislikeCount", "myReaction", "isAuthor", "createdAt", "updatedAt"};
    private static final String[] COMMENT_KEYS = {"commentId", "parentCommentId", "status", "content", "imageUrls",
            "author", "likeCount", "dislikeCount", "myReaction", "isAuthor", "createdAt", "updatedAt", "replies"};

    @MockitoBean
    private CommunityCommentCommandService commentCommandService;
    @MockitoBean
    private CommunityReactionService reactionService;
    @MockitoBean
    private CommunityReportService reportService;

    private static ReplyResponse reply() {
        return new ReplyResponse(6L, 5L, CommentStatus.VISIBLE, "답글 수정", List.of(), new AuthorResponse("닉", null),
                0, 0, null, true, AT, AT);
    }

    private static CommentResponse comment() {
        return new CommentResponse(5L, null, CommentStatus.VISIBLE, "댓글 수정", List.of(),
                new AuthorResponse("닉", null), 0, 0, null, true, AT, AT, List.of(reply()));
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("[AC-CM-109-1, AC-CM-109-3, AC-CM-106-1] 최상위 댓글 수정은 200 + CommentResponse 13개 키(replies 포함, parentCommentId null)다")
    void update_topLevel_serializesCommentResponse() throws Exception {
        given(commentCommandService.update(eq(ME), eq(5L), any(CommentUpdateRequest.class))).willReturn(comment());

        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"댓글 수정\",\"imageUrls\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(keySet("$.data", COMMENT_KEYS))
                .andExpect(jsonPath("$.data.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data.replies", hasSize(1)));

        verify(commentCommandService).update(ME, 5L, new CommentUpdateRequest("댓글 수정", List.of()));
    }

    @Test
    @DisplayName("[AC-CM-217-1, AC-CM-218-1, AC-CM-218-2] 답글 수정은 200 + ReplyResponse 12개 키이고 replies 키가 없다(깊이 2는 응답 구조로도 표현 불가)")
    void update_reply_serializesReplyResponseWithoutRepliesKey() throws Exception {
        given(commentCommandService.update(eq(ME), eq(6L), any(CommentUpdateRequest.class))).willReturn(reply());

        mockMvc.perform(put("/community/comments/6").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"답글 수정\"}"))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", REPLY_KEYS))
                .andExpect(jsonPath("$.data.parentCommentId").value(5))
                .andExpect(jsonPath("$.data.status").value("VISIBLE"));
    }

    @Test
    @DisplayName("[AC-CM-101-1, AC-CM-102-1] 수정 본문이 공백뿐/1001자/키 없음이면 400이고 서비스는 호출되지 않는다")
    void update_validationFailures() throws Exception {
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").exists());
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + repeat('라', 1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageUrls\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(commentCommandService);
    }

    @Test
    @DisplayName("[AC-CM-110-1, AC-CM-111-1, AC-CM-111-3, AC-CM-181-2] 수정의 403/404/410(댓글)/410(글) 매핑과 문구")
    void update_errorMappings() throws Exception {
        given(commentCommandService.update(eq(ME), eq(5L), any(CommentUpdateRequest.class)))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED));
        String body = "{\"content\":\"c\"}";

        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 댓글입니다."));
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("신고로 숨김 처리된 댓글입니다."));
        mockMvc.perform(put("/community/comments/5").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("신고로 숨김 처리된 게시글입니다."));
    }

    @Test
    @DisplayName("commentId가 숫자가 아니면 400 타입 불일치(commentId)다")
    void update_nonNumericId_returns400() throws Exception {
        mockMvc.perform(put("/community/comments/abc").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"c\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: commentId"));
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("[AC-CM-112-1, AC-CM-3-2] 삭제는 본문 없는 204이고 토큰 계정과 commentId로 위임한다")
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/community/comments/5").header("Authorization", bearer()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(commentCommandService).delete(ME, 5L);
    }

    @Test
    @DisplayName("[AC-CM-110-1, AC-CM-112-3] 타인 댓글 삭제는 403, 재삭제는 404다")
    void delete_forbiddenAndAlreadyDeleted() throws Exception {
        willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND))
                .given(commentCommandService).delete(ME, 5L);

        mockMvc.perform(delete("/community/comments/5").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/community/comments/5").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ---------- 반응 ----------

    @Test
    @DisplayName("[AC-CM-129-1] 댓글 반응 PUT은 게시글과 같은 {myReaction, likeCount, dislikeCount} 응답 형태다")
    void reaction_sameShapeAsPost() throws Exception {
        given(reactionService.reactToComment(ME, 5L, ReactionChoice.DISLIKE))
                .willReturn(new ReactionResponse(ReactionType.DISLIKE, 0, 1));

        mockMvc.perform(put("/community/comments/5/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"DISLIKE\"}"))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", "myReaction", "likeCount", "dislikeCount"))
                .andExpect(jsonPath("$.data.myReaction").value("DISLIKE"))
                .andExpect(jsonPath("$.data.dislikeCount").value(1));
    }

    @Test
    @DisplayName("[AC-CM-126-1] 댓글 반응도 LOVE / 값 누락은 400이고 서비스는 호출되지 않는다")
    void reaction_invalid_returns400() throws Exception {
        mockMvc.perform(put("/community/comments/5/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LOVE\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/community/comments/5/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reaction").exists());

        verifyNoInteractions(reactionService);
    }

    @Test
    @DisplayName("[AC-CM-129-2] 삭제 댓글 반응은 404, 블라인드 댓글 반응은 410 COMMUNITY_COMMENT_BLINDED다")
    void reaction_notFoundAndBlinded() throws Exception {
        given(reactionService.reactToComment(ME, 5L, ReactionChoice.LIKE))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED));

        mockMvc.perform(put("/community/comments/5/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/community/comments/5/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("신고로 숨김 처리된 댓글입니다."));
    }

    // ---------- 신고 ----------

    @Test
    @DisplayName("[AC-CM-180-1] 댓글 신고는 본문 없이 200 + data:null이다")
    void report_returns200() throws Exception {
        mockMvc.perform(post("/community/comments/5/report").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()));

        verify(reportService).reportComment(ME, 5L);
    }

    @Test
    @DisplayName("[AC-CM-182-1, AC-CM-182-2] 자기 댓글 신고는 403(글·댓글 공용 문구), 삭제 댓글 신고는 404 COMMUNITY_COMMENT_NOT_FOUND다")
    void report_selfAndDeleted() throws Exception {
        willThrow(new BusinessException(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND))
                .given(reportService).reportComment(ME, 5L);

        mockMvc.perform(post("/community/comments/5/report").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("자신의 글은 신고할 수 없습니다."));
        mockMvc.perform(post("/community/comments/5/report").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 댓글입니다."));
    }
}
