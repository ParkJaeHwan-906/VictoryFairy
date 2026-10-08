package com.skhynix.user.community.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.skhynix.user.community.dto.MyPostSummaryResponse;
import com.skhynix.user.community.dto.PageResponse;
import com.skhynix.user.community.dto.PostDetailResponse;
import com.skhynix.user.community.dto.PostRequest;
import com.skhynix.user.community.dto.PostSort;
import com.skhynix.user.community.dto.PostSummaryResponse;
import com.skhynix.user.community.dto.ReactionChoice;
import com.skhynix.user.community.dto.ReactionResponse;
import com.skhynix.user.community.dto.ReplyResponse;
import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityCommentQueryService;
import com.skhynix.user.community.service.CommunityPostCommandService;
import com.skhynix.user.community.service.CommunityPostQueryService;
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
 * {@link CommunityPostController} 슬라이스 - 게시글 11개 엔드포인트의 상태코드, 응답 키 집합, 검증 실패 400 모양,
 * 오류 코드 매핑(404/403/410). 서비스는 목이고 판정 로직 자체는 서비스 단위 테스트 몫이다.
 * 요구사항: {@code docs/requirements/user/community.md}.
 */
@WebMvcTest(CommunityPostController.class)
@ContextConfiguration(classes = CommunityPostController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CommunityPostControllerTest extends CommunitySliceSupport {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
    private static final String[] PAGE_KEYS = {"content", "page", "size", "totalElements", "totalPages", "hasNext"};
    private static final String[] SUMMARY_KEYS = {"postId", "categoryId", "categoryName", "title", "thumbnailUrl",
            "author", "viewCount", "likeCount", "dislikeCount", "commentCount", "createdAt"};
    private static final String[] DETAIL_KEYS = {"postId", "categoryId", "categoryName", "title", "content",
            "imageUrls", "author", "viewCount", "likeCount", "dislikeCount", "commentCount", "myReaction",
            "isAuthor", "createdAt", "updatedAt"};
    private static final String[] REPLY_KEYS = {"commentId", "parentCommentId", "status", "content", "imageUrls",
            "author", "likeCount", "dislikeCount", "myReaction", "isAuthor", "createdAt", "updatedAt"};
    private static final String[] COMMENT_KEYS = {"commentId", "parentCommentId", "status", "content", "imageUrls",
            "author", "likeCount", "dislikeCount", "myReaction", "isAuthor", "createdAt", "updatedAt", "replies"};

    @MockitoBean
    private CommunityPostQueryService postQueryService;
    @MockitoBean
    private CommunityPostCommandService postCommandService;
    @MockitoBean
    private CommunityCommentQueryService commentQueryService;
    @MockitoBean
    private CommunityCommentCommandService commentCommandService;
    @MockitoBean
    private CommunityReactionService reactionService;
    @MockitoBean
    private CommunityReportService reportService;

    private static PostSummaryResponse summary(long id, String thumbnail) {
        return new PostSummaryResponse(id, 3L, "자유게시판", "제목" + id, thumbnail, new AuthorResponse("닉", null),
                1, 0, 0, 0, AT);
    }

    private static PostDetailResponse detail(ReactionType myReaction) {
        return new PostDetailResponse(1L, 3L, "자유게시판", "제목", "본문", List.of("community/a.jpg"),
                new AuthorResponse("닉", null), 1, 0, 0, 0, myReaction, true, AT, AT);
    }

    private static <T> PageResponse<T> page(List<T> content) {
        return new PageResponse<>(content, 0, 20, content.size(), 1, false);
    }

    private static String postBody(String title, String content) {
        return "{\"categoryId\":11,\"title\":" + quote(title) + ",\"content\":" + quote(content)
                + ",\"imageUrls\":[]}";
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ---------- 작성 ----------

    @Test
    @DisplayName("[AC-CM-20-1, AC-CM-3-1, AC-CM-5-1] 작성은 201 + ApiResponse{postId 정수}이고 서비스는 토큰의 계정 id와 요청 본문으로 호출된다")
    void create_returns201WithPostId() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class))).willReturn(1L);

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("오늘 직관 후기", "9회말…")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.postId").value(1))
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(keySet("$.data", "postId"));

        verify(postCommandService).create(ME, new PostRequest(11L, "오늘 직관 후기", "9회말…", List.of()));
    }

    @Test
    @DisplayName("[AC-CM-2-1] 본문에 userAccountId·uid를 실어도 무시되고 서비스는 토큰에서 해석한 id로만 호출된다")
    void create_ignoresAccountIdentifiersInBody() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class))).willReturn(1L);

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":11,\"title\":\"t\",\"content\":\"c\","
                                + "\"userAccountId\":9999,\"uid\":\"someone-else\"}"))
                .andExpect(status().isCreated());

        verify(postCommandService).create(ME, new PostRequest(11L, "t", "c", null));
    }

    @Test
    @DisplayName("[AC-CM-22-1] 제목이 공백뿐이면 400 + data.title 필드 메시지이고 서비스는 호출되지 않는다")
    void create_blankTitle_returns400WithTitleError() throws Exception {
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("   ", "본문")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.title").exists())
                .andExpect(keySet("$.data", "title"));

        verifyNoInteractions(postCommandService);
    }

    @Test
    @DisplayName("[AC-CM-22-1, AC-CM-23-1] 제목이 null이거나 본문이 빈 문자열이면 각각 400 + 해당 필드 키가 data에 있다")
    void create_nullTitleOrEmptyContent_returns400() throws Exception {
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":11,\"content\":\"c\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").exists());
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("t", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").exists());

        verifyNoInteractions(postCommandService);
    }

    @Test
    @DisplayName("[AC-CM-24-1, AC-CM-24-2] 제목 101자는 400 data.title, 정확히 100자는 201이다(경계)")
    void create_titleLengthBoundary() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class))).willReturn(1L);

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody(repeat('가', 101), "c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").exists());
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody(repeat('가', 100), "c")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[AC-CM-25-1, AC-CM-25-2] 본문 5001자는 400 data.content, 정확히 5000자는 201이다(경계)")
    void create_contentLengthBoundary() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class))).willReturn(1L);

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("t", repeat('나', 5001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").exists());
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("t", repeat('나', 5000))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[AC-CM-26-1] categoryId 키가 없으면 400 + data.categoryId다(카테고리는 필수)")
    void create_missingCategoryId_returns400() throws Exception {
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.categoryId").exists());

        verifyNoInteractions(postCommandService);
    }

    @Test
    @DisplayName("[AC-CM-27-1] 존재하지 않는 카테고리(서비스가 404 던짐)는 404 + 계약 문구다")
    void create_unknownCategory_returns404() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class)))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_CATEGORY_NOT_FOUND));

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("t", "c")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("존재하지 않는 카테고리입니다."));
    }

    @Test
    @DisplayName("[AC-CM-149-1, AC-CM-152-1] 이미지 EP/상한 위반은 서비스의 400 코드가 그대로 응답 문구로 나간다")
    void create_imageErrors_mapTo400Messages() throws Exception {
        given(postCommandService.create(eq(ME), any(PostRequest.class)))
                .willThrow(new BusinessException(ErrorCode.INVALID_COMMUNITY_IMAGE_ENDPOINT))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_IMAGE_LIMIT_EXCEEDED));

        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody("t", "c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("유효하지 않은 이미지입니다."));
        mockMvc.perform(post("/community/posts").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody("t", "c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미지는 게시글 5장, 댓글 3장까지 첨부할 수 있습니다."));
    }

    // ---------- 전체 목록 ----------

    @Test
    @DisplayName("[AC-CM-7-1, AC-CM-6-1, AC-CM-41-1, AC-CM-42-1] 파라미터 없이 요청하면 (null, latest, 0, 20)으로 위임하고 페이지 키 6개·항목 키 11개(content 없음)로 응답한다")
    void getPosts_defaults() throws Exception {
        given(postQueryService.getPosts(null, PostSort.latest, 0, 20))
                .willReturn(page(List.of(summary(2, "community/a.jpg"), summary(1, null))));

        mockMvc.perform(get("/community/posts").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(keySet("$.data", PAGE_KEYS))
                .andExpect(keySet("$.data.content[0]", SUMMARY_KEYS))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value("community/a.jpg"))
                .andExpect(jsonPath("$.data.content[1].thumbnailUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].author.nickname").value("닉"))
                .andExpect(keySet("$.data.content[0].author", "nickname", "profileImgUrl"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("[AC-CM-46-1, AC-CM-43-1, AC-CM-44-1, AC-CM-8-2] categoryId/sort/page/size 쿼리가 그대로 서비스에 전달되고 size=50은 통과한다")
    void getPosts_passesQueryParameters() throws Exception {
        given(postQueryService.getPosts(3L, PostSort.likes, 1, 50)).willReturn(page(List.of()));
        given(postQueryService.getPosts(3L, PostSort.views, 0, 20)).willReturn(page(List.of()));

        mockMvc.perform(get("/community/posts?categoryId=3&sort=likes&page=1&size=50")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/community/posts?categoryId=3&sort=views").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(postQueryService).getPosts(3L, PostSort.likes, 1, 50);
        verify(postQueryService).getPosts(3L, PostSort.views, 0, 20);
    }

    @Test
    @DisplayName("[AC-CM-8-1] size=51은 400 + ApiResponse 래퍼(data.size 메시지)이고 서비스는 호출되지 않는다 - @Max는 HandlerMethodValidationException 핸들러를 탄다(500 아님)")
    void getPosts_sizeOverMax_returns400WrappedWithSizeMessage() throws Exception {
        mockMvc.perform(get("/community/posts?size=51").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.data.size").value("size는 50 이하여야 합니다."));

        verifyNoInteractions(postQueryService);
    }

    @Test
    @DisplayName("[AC-CM-8-1, AC-CM-8-3] size=0 / size=-5는 400 data.size, page=-1은 400 data.page다")
    void getPosts_sizeBelowMinOrNegativePage_returns400() throws Exception {
        mockMvc.perform(get("/community/posts?size=0").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.size").value("size는 1 이상이어야 합니다."));
        mockMvc.perform(get("/community/posts?size=-5").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.size").exists());
        mockMvc.perform(get("/community/posts?page=-1").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").value("page는 0 이상이어야 합니다."));

        verifyNoInteractions(postQueryService);
    }

    @Test
    @DisplayName("[AC-CM-8-4] page=abc는 400 + '요청 파라미터 형식이 올바르지 않습니다: page'(기존 handleTypeMismatch)다")
    void getPosts_nonNumericPage_returns400TypeMismatch() throws Exception {
        mockMvc.perform(get("/community/posts?page=abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: page"));
    }

    @Test
    @DisplayName("[AC-CM-45-1] sort=hot은 400 + '요청 파라미터 형식이 올바르지 않습니다: sort'이고 대문자 LIKES도 거절한다(대소문자 구분)")
    void getPosts_invalidSort_returns400() throws Exception {
        mockMvc.perform(get("/community/posts?sort=hot").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: sort"));
        mockMvc.perform(get("/community/posts?sort=LIKES").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(postQueryService);
    }

    @Test
    @DisplayName("[AC-CM-47-1] 존재하지 않는 categoryId 필터는 404가 아니라 200 + 빈 content다")
    void getPosts_unknownCategoryFilter_returns200Empty() throws Exception {
        given(postQueryService.getPosts(999L, PostSort.latest, 0, 20)).willReturn(page(List.of()));

        mockMvc.perform(get("/community/posts?categoryId=999").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ---------- 인기 ----------

    @Test
    @DisplayName("[AC-CM-56-1, AC-CM-55-1] 인기 목록은 페이지 래퍼 없는 배열이고 항목 키는 PostSummary 11개이며 ?page= 는 무시된다")
    void popular_returnsPlainArrayIgnoringPage() throws Exception {
        given(postQueryService.getPopularPosts(null)).willReturn(List.of(summary(5, null), summary(3, null)));

        mockMvc.perform(get("/community/posts/popular?page=3").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(keySet("$.data[0]", SUMMARY_KEYS));

        verify(postQueryService).getPopularPosts(null);
    }

    @Test
    @DisplayName("[AC-CM-53-2, AC-CM-54-1] 후보가 없으면 data: [] (null 아님)이고 categoryId는 그대로 전달된다")
    void popular_emptyAndCategoryPassThrough() throws Exception {
        given(postQueryService.getPopularPosts(3L)).willReturn(List.of());

        mockMvc.perform(get("/community/posts/popular?categoryId=3").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":[]")));
    }

    // ---------- 내 글 ----------

    @Test
    @DisplayName("[AC-CM-66-1, AC-CM-67-1] 내 글 항목은 PostSummary 11개 + blinded 12개 키이고 sort 파라미터는 무시된다(토큰 계정으로 위임)")
    void myPosts_keysIncludeBlinded_andSortIgnored() throws Exception {
        given(postQueryService.getMyPosts(ME, 0, 20)).willReturn(page(List.of(
                new MyPostSummaryResponse(1L, 3L, "자유게시판", "t", null, new AuthorResponse("닉", null),
                        0, 0, 0, 0, AT, true))));

        mockMvc.perform(get("/community/posts/me?sort=likes").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", PAGE_KEYS))
                .andExpect(keySet("$.data.content[0]", "postId", "categoryId", "categoryName", "title",
                        "thumbnailUrl", "author", "viewCount", "likeCount", "dislikeCount", "commentCount",
                        "createdAt", "blinded"))
                .andExpect(jsonPath("$.data.content[0].blinded").value(true));

        verify(postQueryService).getMyPosts(ME, 0, 20);
    }

    @Test
    @DisplayName("/posts/me는 {postId} 매핑에 먹히지 않는다 - 상세 서비스가 아니라 내 글 서비스가 호출된다")
    void myPosts_isNotParsedAsPostId() throws Exception {
        given(postQueryService.getMyPosts(eq(ME), anyInt(), anyInt())).willReturn(page(List.of()));

        mockMvc.perform(get("/community/posts/me").header("Authorization", bearer()))
                .andExpect(status().isOk());

        verify(postQueryService, org.mockito.Mockito.never()).getPost(any(), anyLong());
    }

    // ---------- 상세 ----------

    @Test
    @DisplayName("[AC-CM-60-1, AC-CM-60-2, AC-CM-64-1] 상세 키는 정확히 15개이고 myReaction이 없으면 키는 있되 null, comments 키는 없다")
    void detail_keySetWithNullMyReaction() throws Exception {
        given(postQueryService.getPost(ME, 1L)).willReturn(detail(null));

        mockMvc.perform(get("/community/posts/1").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", DETAIL_KEYS))
                .andExpect(jsonPath("$.data.myReaction").value(nullValue()))
                .andExpect(jsonPath("$.data.isAuthor").value(true))
                .andExpect(jsonPath("$.data.imageUrls[0]").value("community/a.jpg"));
    }

    @Test
    @DisplayName("[AC-CM-60-2] 내 반응이 있으면 myReaction은 'LIKE' 문자열이다")
    void detail_myReactionSerializedAsName() throws Exception {
        given(postQueryService.getPost(ME, 1L)).willReturn(detail(ReactionType.LIKE));

        mockMvc.perform(get("/community/posts/1").header("Authorization", bearer()))
                .andExpect(jsonPath("$.data.myReaction").value("LIKE"));
    }

    @Test
    @DisplayName("[AC-CM-62-1, AC-CM-62-2] 없거나 삭제된 글은 404 + '존재하지 않는 게시글입니다.'다")
    void detail_notFound_returns404() throws Exception {
        given(postQueryService.getPost(ME, 999999L))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND));

        mockMvc.perform(get("/community/posts/999999").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("존재하지 않는 게시글입니다."));
    }

    @Test
    @DisplayName("[AC-CM-63-1] 블라인드 글은 410 + '신고로 숨김 처리된 게시글입니다.'이고 data는 null이다")
    void detail_blinded_returns410() throws Exception {
        given(postQueryService.getPost(ME, 1L)).willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED));

        mockMvc.perform(get("/community/posts/1").header("Authorization", bearer()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("신고로 숨김 처리된 게시글입니다."));
    }

    @Test
    @DisplayName("[AC-CM-62-3] /posts/abc는 400 타입 불일치다(postId)")
    void detail_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/community/posts/abc").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 파라미터 형식이 올바르지 않습니다: postId"));
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("[AC-CM-80-1] 수정은 200 + PostDetail 15개 키이고 서비스는 (토큰 계정, 경로 postId, 본문)으로 호출된다")
    void update_returns200WithDetail() throws Exception {
        given(postCommandService.update(eq(ME), eq(1L), any(PostRequest.class))).willReturn(detail(null));

        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":2,\"title\":\"수정\",\"content\":\"본문\",\"imageUrls\":[]}"))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", DETAIL_KEYS));

        verify(postCommandService).update(ME, 1L, new PostRequest(2L, "수정", "본문", List.of()));
    }

    @Test
    @DisplayName("[AC-CM-80-2, AC-CM-81-1, AC-CM-81-3] PUT은 부분 생략 불가 - title 키 없음·빈 제목·101자 제목은 400이고 서비스는 호출되지 않는다")
    void update_validationFailures_return400() throws Exception {
        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":2,\"content\":\"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.title").exists());
        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody("", "c")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody(repeat('가', 101), "c")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(postCommandService);
    }

    @Test
    @DisplayName("[AC-CM-82-1, AC-CM-84-1] 타인 글 수정은 403 + '작성자만 수정·삭제할 수 있습니다.', 블라인드 글 수정은 410이다")
    void update_forbiddenAndBlinded_mapToStatus() throws Exception {
        given(postCommandService.update(eq(ME), eq(1L), any(PostRequest.class)))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED));

        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody("t", "c")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("작성자만 수정·삭제할 수 있습니다."));
        mockMvc.perform(put("/community/posts/1").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(postBody("t", "c")))
                .andExpect(status().isGone());
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("[AC-CM-90-1, AC-CM-3-2] 삭제는 본문 없는 204이고 토큰 계정과 postId로 위임한다")
    void delete_returns204WithoutBody() throws Exception {
        mockMvc.perform(delete("/community/posts/1").header("Authorization", bearer()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(postCommandService).delete(ME, 1L);
    }

    @Test
    @DisplayName("[AC-CM-92-1, AC-CM-93-1] 타인 글 삭제는 403, 이미 삭제된 글의 재삭제는 404다")
    void delete_forbiddenAndAlreadyDeleted() throws Exception {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.COMMUNITY_NOT_AUTHOR))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND))
                .given(postCommandService).delete(ME, 1L);

        mockMvc.perform(delete("/community/posts/1").header("Authorization", bearer()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/community/posts/1").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ---------- 반응 ----------

    @Test
    @DisplayName("[AC-CM-120-1] LIKE는 200 + {myReaction:LIKE, likeCount:1, dislikeCount:0} 키 3개이고 서비스에 ReactionChoice.LIKE가 전달된다")
    void reaction_like_returnsStateAndCounts() throws Exception {
        given(reactionService.reactToPost(ME, 1L, ReactionChoice.LIKE))
                .willReturn(new ReactionResponse(ReactionType.LIKE, 1, 0));

        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", "myReaction", "likeCount", "dislikeCount"))
                .andExpect(jsonPath("$.data.myReaction").value("LIKE"))
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.dislikeCount").value(0));
    }

    @Test
    @DisplayName("[AC-CM-122-1] NONE은 myReaction:null로 응답한다(키는 유지)")
    void reaction_none_returnsNullMyReaction() throws Exception {
        given(reactionService.reactToPost(ME, 1L, ReactionChoice.NONE))
                .willReturn(new ReactionResponse(null, 0, 0));

        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"NONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(hasKey("myReaction")))
                .andExpect(jsonPath("$.data.myReaction").value(nullValue()));
    }

    @Test
    @DisplayName("[AC-CM-126-1] 범위 밖 값 LOVE는 400 + 본문 읽기 실패 래퍼, 값 누락 {}는 400 + data.reaction이고 서비스는 호출되지 않는다")
    void reaction_invalidOrMissing_returns400() throws Exception {
        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LOVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."));
        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.reaction").exists());

        verifyNoInteractions(reactionService);
    }

    @Test
    @DisplayName("[AC-CM-127-1, AC-CM-127-2] 삭제 글 반응은 404, 블라인드 글 반응은 410이다")
    void reaction_notFoundAndBlinded() throws Exception {
        given(reactionService.reactToPost(ME, 1L, ReactionChoice.LIKE))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED));

        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/community/posts/1/reaction").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"LIKE\"}"))
                .andExpect(status().isGone());
    }

    // ---------- 신고 ----------

    @Test
    @DisplayName("[AC-CM-170-1, AC-CM-174-1] 신고는 본문 없이 200 + {success:true,data:null,message:null}이고, 본문을 보내도 무시된다")
    void report_returns200EnvelopeWithNullData() throws Exception {
        mockMvc.perform(post("/community/posts/1/report").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value(nullValue()));
        mockMvc.perform(post("/community/posts/1/report").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"욕설\"}"))
                .andExpect(status().isOk());

        verify(reportService, org.mockito.Mockito.times(2)).reportPost(ME, 1L);
    }

    @Test
    @DisplayName("[AC-CM-171-1, AC-CM-173-1] 자기 글 신고는 403 + '자신의 글은 신고할 수 없습니다.', 삭제 글 신고는 404다")
    void report_selfAndNotFound() throws Exception {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.COMMUNITY_SELF_REPORT_NOT_ALLOWED))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND))
                .given(reportService).reportPost(ME, 1L);

        mockMvc.perform(post("/community/posts/1/report").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("자신의 글은 신고할 수 없습니다."));
        mockMvc.perform(post("/community/posts/1/report").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
    }

    // ---------- 댓글 작성 / 목록 ----------

    @Test
    @DisplayName("[AC-CM-100-1, AC-CM-114-1] 댓글 작성은 201 + {commentId}이고 parentCommentId가 서비스 요청에 실린다")
    void createComment_returns201AndPassesParent() throws Exception {
        given(commentCommandService.create(eq(ME), eq(1L), any())).willReturn(70L);

        mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"ㄹㅇ\",\"imageUrls\":[],\"parentCommentId\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.commentId").value(70))
                .andExpect(keySet("$.data", "commentId"));

        verify(commentCommandService).create(ME, 1L,
                new com.skhynix.user.community.dto.CommentRequest("ㄹㅇ", List.of(), 5L));
    }

    @Test
    @DisplayName("[AC-CM-101-1, AC-CM-102-1, AC-CM-102-2] 댓글 내용이 공백뿐/1001자면 400 data.content, 1000자는 201이다")
    void createComment_contentValidation() throws Exception {
        given(commentCommandService.create(eq(ME), eq(1L), any())).willReturn(70L);

        mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.content").exists());
        mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + repeat('다', 1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + repeat('다', 1000) + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[AC-CM-103-1, AC-CM-104-1, AC-CM-115-1, AC-CM-116-1, AC-CM-117-2] 댓글 작성의 404/410/400(DEPTH)/410(부모 블라인드)이 각 상태코드와 문구로 나간다")
    void createComment_errorMappings() throws Exception {
        given(commentCommandService.create(eq(ME), eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_COMMENT_BLINDED));
        String body = "{\"content\":\"c\",\"parentCommentId\":9}";

        for (int expectedStatus : new int[] {404, 410, 404, 400, 410}) {
            mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(expectedStatus));
        }
    }

    @Test
    @DisplayName("[AC-CM-116-1] 깊이 초과의 문구는 '답글에는 답글을 달 수 없습니다.'다")
    void createComment_depthExceededMessage() throws Exception {
        given(commentCommandService.create(eq(ME), eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_REPLY_DEPTH_EXCEEDED));

        mockMvc.perform(post("/community/posts/1/comments").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"c\",\"parentCommentId\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("답글에는 답글을 달 수 없습니다."));
    }

    @Test
    @DisplayName("[AC-CM-105-1, AC-CM-106-1, AC-CM-118-3, AC-CM-212-1] 댓글 목록: 페이지 키 6개, 항목 키 13개(viewCount 없음), 답글 키 12개, 자리 표식은 키는 그대로 값만 비운다")
    void getComments_keySetsAndPlaceholderShape() throws Exception {
        ReplyResponse reply = new ReplyResponse(6L, 5L, CommentStatus.VISIBLE, "답글", List.of(),
                new AuthorResponse("남", null), 0, 0, null, false, AT, AT);
        CommentResponse visible = new CommentResponse(4L, null, CommentStatus.VISIBLE, "댓글", List.of("community/a.jpg"),
                new AuthorResponse("닉", null), 2, 1, ReactionType.DISLIKE, true, AT, AT, List.of());
        CommentResponse placeholder = new CommentResponse(5L, null, CommentStatus.DELETED, null, List.of(), null,
                0, 0, null, false, AT, null, List.of(reply));
        given(commentQueryService.getComments(ME, 1L, 0, 20)).willReturn(page(List.of(visible, placeholder)));

        mockMvc.perform(get("/community/posts/1/comments").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(keySet("$.data", PAGE_KEYS))
                .andExpect(keySet("$.data.content[0]", COMMENT_KEYS))
                .andExpect(jsonPath("$.data.content[0].status").value("VISIBLE"))
                .andExpect(jsonPath("$.data.content[0].parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].replies", hasSize(0)))
                .andExpect(keySet("$.data.content[1]", COMMENT_KEYS))
                .andExpect(jsonPath("$.data.content[1].status").value("DELETED"))
                .andExpect(jsonPath("$.data.content[1].content").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].author").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].imageUrls", hasSize(0)))
                .andExpect(jsonPath("$.data.content[1].likeCount").value(0))
                .andExpect(jsonPath("$.data.content[1].isAuthor").value(false))
                .andExpect(jsonPath("$.data.content[1].updatedAt").value(nullValue()))
                .andExpect(keySet("$.data.content[1].replies[0]", REPLY_KEYS))
                .andExpect(jsonPath("$.data.content[1].replies[0].parentCommentId").value(5))
                .andExpect(jsonPath("$.data.content[1].replies[0].status").value("VISIBLE"));
    }

    @Test
    @DisplayName("[AC-CM-8-1, AC-CM-8-3] 댓글 목록도 size=51 / page=-1이면 400이고 서비스는 호출되지 않는다")
    void getComments_pagingValidation() throws Exception {
        mockMvc.perform(get("/community/posts/1/comments?size=51").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.size").exists());
        mockMvc.perform(get("/community/posts/1/comments?page=-1").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").exists());

        verifyNoInteractions(commentQueryService);
    }

    @Test
    @DisplayName("[AC-CM-105-4, AC-CM-104-1] 댓글 목록의 소속 글 404/410이 그대로 나간다")
    void getComments_postNotFoundOrBlinded() throws Exception {
        given(commentQueryService.getComments(ME, 1L, 0, 20))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_NOT_FOUND))
                .willThrow(new BusinessException(ErrorCode.COMMUNITY_POST_BLINDED));

        mockMvc.perform(get("/community/posts/1/comments").header("Authorization", bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/community/posts/1/comments").header("Authorization", bearer()))
                .andExpect(status().isGone());
    }
}
