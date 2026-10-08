package com.skhynix.user.community.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.user.community.service.CommunityCategoryService;
import com.skhynix.user.community.service.CommunityCommentCommandService;
import com.skhynix.user.community.service.CommunityCommentQueryService;
import com.skhynix.user.community.service.CommunityImageService;
import com.skhynix.user.community.service.CommunityPostCommandService;
import com.skhynix.user.community.service.CommunityPostQueryService;
import com.skhynix.user.community.service.CommunityReactionService;
import com.skhynix.user.community.service.CommunityReportService;
import com.skhynix.user.global.config.SecurityConfig;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 커뮤니티 17개 엔드포인트 전부가 토큰 없이 401 인지 - USER-CM-1(AC-CM-1-1, 1-2, 1-3). 실제 {@link SecurityConfig} 를 태우므로
 * 어느 경로든 permitAll 줄이 실수로 추가되면(AC-CM-1-3) 해당 경로의 행이 깨진다. 서비스는 하나도 호출되면 안 된다.
 */
@WebMvcTest
@ContextConfiguration(classes = {CommunityPostController.class, CommunityCommentController.class,
        CommunityCategoryController.class, CommunityImageController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class CommunityAuthenticationTest extends CommunitySliceSupport {

    @MockitoBean
    private CommunityCategoryService categoryService;
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
    @MockitoBean
    private CommunityImageService imageService;

    private static final String POST_BODY = "{\"categoryId\":1,\"title\":\"t\",\"content\":\"c\",\"imageUrls\":[]}";
    private static final String COMMENT_BODY = "{\"content\":\"c\",\"imageUrls\":[]}";
    private static final String REACTION_BODY = "{\"reaction\":\"LIKE\"}";

    private static Arguments json(String name, MockHttpServletRequestBuilder builder, String body) {
        Supplier<RequestBuilder> supplier =
                () -> builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return Arguments.of(name, supplier);
    }

    private static Arguments plain(String name, RequestBuilder builder) {
        Supplier<RequestBuilder> supplier = () -> builder;
        return Arguments.of(name, supplier);
    }

    static Stream<Arguments> seventeenEndpoints() {
        return Stream.of(
                plain("GET /community/categories", get("/community/categories")),
                json("POST /community/posts", post("/community/posts"), POST_BODY),
                plain("GET /community/posts", get("/community/posts")),
                plain("GET /community/posts/popular", get("/community/posts/popular")),
                plain("GET /community/posts/me", get("/community/posts/me")),
                plain("GET /community/posts/{postId}", get("/community/posts/1")),
                json("PUT /community/posts/{postId}", put("/community/posts/1"), POST_BODY),
                plain("DELETE /community/posts/{postId}", delete("/community/posts/1")),
                json("PUT /community/posts/{postId}/reaction", put("/community/posts/1/reaction"), REACTION_BODY),
                plain("POST /community/posts/{postId}/report", post("/community/posts/1/report")),
                json("POST /community/posts/{postId}/comments", post("/community/posts/1/comments"), COMMENT_BODY),
                plain("GET /community/posts/{postId}/comments", get("/community/posts/1/comments")),
                json("PUT /community/comments/{commentId}", put("/community/comments/5"), COMMENT_BODY),
                plain("DELETE /community/comments/{commentId}", delete("/community/comments/5")),
                json("PUT /community/comments/{commentId}/reaction", put("/community/comments/5/reaction"),
                        REACTION_BODY),
                plain("POST /community/comments/{commentId}/report", post("/community/comments/5/report")),
                plain("POST /community/images", multipart("/community/images").file(
                        new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2, 3}))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seventeenEndpoints")
    @DisplayName("[AC-CM-1-1, AC-CM-1-2, AC-CM-1-3] 토큰 없이 요청하면 401 + 공통 실패 본문이고 서비스는 호출되지 않는다")
    void withoutToken_returns401(String name, Supplier<RequestBuilder> request) throws Exception {
        mockMvc.perform(request.get())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(UNAUTHENTICATED_MESSAGE));

        verifyNoInteractions(categoryService, postQueryService, postCommandService, commentQueryService,
                commentCommandService, reactionService, reportService, imageService);
    }
}
