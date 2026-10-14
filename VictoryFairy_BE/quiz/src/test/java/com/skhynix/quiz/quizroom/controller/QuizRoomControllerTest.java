package com.skhynix.quiz.quizroom.controller;

import static com.skhynix.quiz.quizroom.QuizRoomFixtures.authenticatedAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quizroom.dto.AnswerResponse;
import com.skhynix.quiz.quizroom.dto.AuthorResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomLikeResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomOptionResponse;
import com.skhynix.quiz.quizroom.dto.QuizRoomResultResponse;
import com.skhynix.quiz.quizroom.dto.ResultQuizResponse;
import com.skhynix.quiz.quizroom.dto.RoomDetailResponse;
import com.skhynix.quiz.quizroom.dto.RoomQuizResponse;
import com.skhynix.quiz.quizroom.dto.RoomSummaryResponse;
import com.skhynix.quiz.quizroom.service.QuizRoomAnswerService;
import com.skhynix.quiz.quizroom.service.QuizRoomLikeService;
import com.skhynix.quiz.quizroom.service.QuizRoomService;
import com.skhynix.quiz.quizroom.service.QuizRoomSubscriptionService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

/**
 * 퀴즈방 조회·제출·추천·구독 엔드포인트 슬라이스. 서비스는 목이라 정책 자체는 서비스 테스트가 맡고,
 * 여기서는 인증 배선, 상태 코드, 응답 JSON 키 집합(정답·내부 식별자 비노출)을 고정한다.
 */
@WebMvcTest(QuizRoomController.class)
@ContextConfiguration(classes = QuizRoomController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizRoomControllerTest {

    private static final Long USER_ID = 7L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 14, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizRoomService roomService;

    @MockitoBean
    private QuizRoomAnswerService answerService;

    @MockitoBean
    private QuizRoomLikeService likeService;

    @MockitoBean
    private QuizRoomSubscriptionService subscriptionService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RoomSummaryResponse summary(long id) {
        return new RoomSummaryResponse(id, "제목" + id, "소개", new AuthorResponse("닉", null), 3, 2L, 1L, CREATED_AT);
    }

    private static RoomDetailResponse detail(long id) {
        return new RoomDetailResponse(id, "제목" + id, "소개", new AuthorResponse("닉", "img.jpg"), 3, 2L, 1L,
                CREATED_AT, false, true);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return QuizRoomRequestBodies.MAPPER.readTree(result.getResponse().getContentAsString());
    }

    // ---- QUIZ-CQR-1 ----

    static Stream<Arguments> allEndpoints() {
        return Stream.of(
                Arguments.of("POST /quiz-rooms", post("/quiz-rooms").contentType(MediaType.APPLICATION_JSON).content("{}")),
                Arguments.of("GET /quiz-rooms", get("/quiz-rooms")),
                Arguments.of("GET /quiz-rooms/popular", get("/quiz-rooms/popular")),
                Arguments.of("GET /quiz-rooms/1", get("/quiz-rooms/1")),
                Arguments.of("GET /quiz-rooms/1/quizzes", get("/quiz-rooms/1/quizzes")),
                Arguments.of("POST answer", post("/quiz-rooms/1/quizzes/11/answer")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0}")),
                Arguments.of("PUT like", put("/quiz-rooms/1/like")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}")),
                Arguments.of("GET subscribe", get("/quiz-rooms/1/subscribe")),
                Arguments.of("DELETE subscribe", delete("/quiz-rooms/1/subscribe")));
    }

    @ParameterizedTest(name = "[QUIZ-CQR-1] 인증 헤더 없이 {0} 을 호출하면 401 과 공통 실패 본문이다")
    @MethodSource("allEndpoints")
    void everyEndpoint_withoutAuthentication_returns401(String label, MockHttpServletRequestBuilder builder)
            throws Exception {
        mockMvc.perform(builder)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        verifyNoInteractions(roomService, answerService, likeService, subscriptionService);
    }

    // ---- 목록 / 인기 (QUIZ-CQR-30~35) ----

    @Test
    @DisplayName("[QUIZ-CQR-31] 목록은 PageResponse 6키, 항목은 RoomSummary 8키이며 문항·정답·계정 식별자 키가 없다")
    void getRooms_returnsPageResponseWithSummaryKeysOnly() throws Exception {
        PageResponse<RoomSummaryResponse> page = new PageResponse<>(List.of(summary(25), summary(24)), 0, 20, 25, 2,
                true);
        given(roomService.getRooms(0, 20)).willReturn(page);

        MvcResult result = mockMvc.perform(get("/quiz-rooms").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(25))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.content[0].roomId").value(25))
                .andReturn();

        JsonNode data = body(result).get("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("content", "page", "size", "totalElements",
                "totalPages", "hasNext");
        assertThat(data.get("content").get(0).propertyNames()).containsExactlyInAnyOrder("roomId", "title",
                "description", "author", "quizCount", "likeCount", "solvingCount", "createdAt");
        assertThat(data.get("content").get(0).get("author").propertyNames())
                .containsExactlyInAnyOrder("nickname", "profileImgUrl");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("userAccountId").doesNotContain("uid");
    }

    @Test
    @DisplayName("[QUIZ-CQR-32] page·size 를 생략하면 page=0, size=20 으로 서비스를 부른다")
    void getRooms_defaults_pageZeroSizeTwenty() throws Exception {
        given(roomService.getRooms(0, 20)).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, false));

        mockMvc.perform(get("/quiz-rooms").with(authenticatedAs(USER_ID))).andExpect(status().isOk());

        verify(roomService).getRooms(0, 20);
    }

    @Test
    @DisplayName("[QUIZ-CQR-32] size 51 은 400, size 50 은 200")
    void getRooms_sizeUpperBoundary() throws Exception {
        mockMvc.perform(get("/quiz-rooms").param("size", "51").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.size").exists());
        verify(roomService, never()).getRooms(anyInt(), anyInt());

        given(roomService.getRooms(0, 50)).willReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, false));
        mockMvc.perform(get("/quiz-rooms").param("size", "50").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[QUIZ-CQR-32] size 0 은 400, page -1 은 400 이고 서비스는 호출되지 않는다")
    void getRooms_invalidPageOrSize_returns400() throws Exception {
        mockMvc.perform(get("/quiz-rooms").param("size", "0").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/quiz-rooms").param("page", "-1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.page").exists());

        verify(roomService, never()).getRooms(anyInt(), anyInt());
    }

    @Test
    @DisplayName("[QUIZ-CQR-34, QUIZ-CQR-35] 인기 목록은 페이징 없이 RoomSummary 배열이고, 방이 없으면 null 이 아니라 빈 배열이다")
    void getPopular_returnsArray_emptyIsNotNull() throws Exception {
        given(roomService.getPopularRooms()).willReturn(List.of());

        mockMvc.perform(get("/quiz-rooms/popular").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("[QUIZ-CQR-35] 인기 목록 항목의 키는 목록과 같고 점수 키는 없다")
    void getPopular_itemKeysEqualSummary() throws Exception {
        given(roomService.getPopularRooms()).willReturn(List.of(summary(3), summary(2)));

        MvcResult result = mockMvc.perform(get("/quiz-rooms/popular").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andReturn();

        JsonNode first = body(result).get("data").get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("roomId", "title", "description", "author",
                "quizCount", "likeCount", "solvingCount", "createdAt");
    }

    // ---- 상세 (QUIZ-CQR-40, 41) ----

    @Test
    @DisplayName("[QUIZ-CQR-40, QUIZ-CQR-3] 상세 키는 RoomSummary 키 + {liked, isAuthor} 이고 작성자 계정 식별자는 없다")
    void getRoom_returnsDetailKeySet() throws Exception {
        given(roomService.getRoom(USER_ID, 1L)).willReturn(detail(1L));

        MvcResult result = mockMvc.perform(get("/quiz-rooms/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.isAuthor").value(true))
                .andExpect(jsonPath("$.data.author.profileImgUrl").value("img.jpg"))
                .andReturn();

        assertThat(body(result).get("data").propertyNames()).containsExactlyInAnyOrder("roomId", "title",
                "description", "author", "quizCount", "likeCount", "solvingCount", "createdAt", "liked", "isAuthor");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("userAccountId").doesNotContain("uid");
    }

    @Test
    @DisplayName("[QUIZ-CQR-3] 프로필 이미지가 없는 작성자는 profileImgUrl 키가 null 로 나간다")
    void getRoom_authorWithoutProfileImage_hasNullProfileImgUrl() throws Exception {
        given(roomService.getRoom(USER_ID, 2L)).willReturn(new RoomDetailResponse(2L, "t", "d",
                new AuthorResponse("닉", null), 1, 0L, 0L, CREATED_AT, false, false));

        MvcResult result = mockMvc.perform(get("/quiz-rooms/2").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode author = body(result).get("data").get("author");
        assertThat(author.has("profileImgUrl")).isTrue();
        assertThat(author.get("profileImgUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("[QUIZ-CQR-41] 없는 방 상세는 404 와 QUIZ_ROOM_NOT_FOUND 메시지, data 는 null")
    void getRoom_notFound_returns404() throws Exception {
        given(roomService.getRoom(USER_ID, 999999L)).willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(get("/quiz-rooms/999999").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈방입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-41] 숫자가 아닌 roomId 는 400 (타입 불일치 핸들러)")
    void getRoom_nonNumericRoomId_returns400() throws Exception {
        mockMvc.perform(get("/quiz-rooms/abc").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(roomService);
    }

    // ---- 퀴즈 리스트 (QUIZ-CQR-42~44, 95) ----

    @Test
    @DisplayName("[QUIZ-CQR-42, QUIZ-CQR-43, QUIZ-CQR-95] 퀴즈 리스트 원소 키는 {quizId,type,question,options} 뿐이고 answer·correct·count·voteCount 키가 없다")
    void getQuizzes_hasNoAnswerOrVoteKeys() throws Exception {
        given(roomService.getQuizzes(1L)).willReturn(List.of(
                new RoomQuizResponse(11L, "객관식", "1994 우승 감독은?", List.of(new QuizRoomOptionResponse(0, "이광환"),
                        new QuizRoomOptionResponse(1, "김성근"))),
                new RoomQuizResponse(12L, "O/X", "LG의 연고지는 서울이다",
                        List.of(new QuizRoomOptionResponse(0, "O"), new QuizRoomOptionResponse(1, "X")))));

        MvcResult result = mockMvc.perform(get("/quiz-rooms/1/quizzes").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].options[1].no").value(1))
                .andExpect(jsonPath("$.data[0].options[1].text").value("김성근"))
                .andExpect(jsonPath("$.data[1].type").value("O/X"))
                .andReturn();

        JsonNode data = body(result).get("data");
        for (JsonNode quiz : data) {
            assertThat(quiz.propertyNames()).containsExactlyInAnyOrder("quizId", "type", "question", "options");
        }
        assertThat(result.getResponse().getContentAsString()).doesNotContain("answer").doesNotContain("correct")
                .doesNotContain("count").doesNotContain("voteCount");
    }

    @Test
    @DisplayName("[QUIZ-CQR-44] 없는 방의 퀴즈 리스트는 404")
    void getQuizzes_roomNotFound_returns404() throws Exception {
        given(roomService.getQuizzes(999999L)).willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(get("/quiz-rooms/999999/quizzes").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈방입니다."));
    }

    // ---- 답 제출 (QUIZ-CQR-50~60) ----

    @Test
    @DisplayName("[QUIZ-CQR-50, QUIZ-CQR-53] 마지막이 아닌 답은 200 {answeredCount,total,result:null} 이고 정답·정오 키가 없다")
    void answer_notLast_returnsNullResult() throws Exception {
        given(answerService.answer(USER_ID, 1L, 11L, 0)).willReturn(new AnswerResponse(1, 3, null));

        MvcResult result = mockMvc.perform(post("/quiz-rooms/1/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answeredCount").value(1))
                .andExpect(jsonPath("$.data.total").value(3))
                .andReturn();

        JsonNode data = body(result).get("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("answeredCount", "total", "result");
        assertThat(data.get("result").isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("answer\"").doesNotContain("correct");
    }

    @Test
    @DisplayName("[QUIZ-CQR-51, QUIZ-CQR-52] 마지막 답의 응답은 result 에 {correctCount,total,quizzes[...]} 를 정해진 키로 싣는다")
    void answer_last_returnsResultWithExactKeys() throws Exception {
        QuizRoomResultResponse resultBody = new QuizRoomResultResponse(1, 2, List.of(
                new ResultQuizResponse(11L, "객관식", "q1", List.of(new QuizRoomOptionResponse(0, "a"),
                        new QuizRoomOptionResponse(1, "b")), 1, 0, false),
                new ResultQuizResponse(12L, "O/X", "q2", List.of(new QuizRoomOptionResponse(0, "O"),
                        new QuizRoomOptionResponse(1, "X")), 0, 0, true)));
        given(answerService.answer(USER_ID, 1L, 12L, 0)).willReturn(new AnswerResponse(2, 2, resultBody));

        MvcResult result = mockMvc.perform(post("/quiz-rooms/1/quizzes/12/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.correctCount").value(1))
                .andExpect(jsonPath("$.data.result.total").value(2))
                .andExpect(jsonPath("$.data.result.quizzes[0].myOption").value(1))
                .andExpect(jsonPath("$.data.result.quizzes[0].answer").value(0))
                .andExpect(jsonPath("$.data.result.quizzes[0].correct").value(false))
                .andExpect(jsonPath("$.data.result.quizzes[1].correct").value(true))
                .andReturn();

        JsonNode quizResult = body(result).get("data").get("result");
        assertThat(quizResult.propertyNames()).containsExactlyInAnyOrder("correctCount", "total", "quizzes");
        assertThat(quizResult.get("quizzes").get(0).propertyNames()).containsExactlyInAnyOrder("quizId", "type",
                "question", "options", "myOption", "answer", "correct");
        // 투표 분포 count 키는 REST 에 없다 (QUIZ-CQR-95)
        assertThat(result.getResponse().getContentAsString()).doesNotContain("voteCount").doesNotContain("\"count\"");
    }

    @Test
    @DisplayName("[QUIZ-CQR-58] option 키가 없는 빈 본문 {} 도 서비스까지 null 로 전달된다 - 404 판정 뒤에 400 이 나와야 하기 때문 (QUIZ-CQR-60)")
    void answer_emptyBody_passesNullOptionToService() throws Exception {
        given(answerService.answer(USER_ID, 1L, 11L, null))
                .willThrow(new BusinessException(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        mockMvc.perform(post("/quiz-rooms/1/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 보기 번호입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-56] 없는 방에 답하면 404 QUIZ_ROOM_NOT_FOUND")
    void answer_roomNotFound_returns404() throws Exception {
        given(answerService.answer(USER_ID, 999999L, 11L, 0))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(post("/quiz-rooms/999999/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈방입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-57] 그 방의 문항이 아닌 quizId 는 404 QUIZ_NOT_FOUND")
    void answer_quizNotInRoom_returns404() throws Exception {
        given(answerService.answer(USER_ID, 1L, 99L, 0)).willThrow(new BusinessException(ErrorCode.QUIZ_NOT_FOUND));

        mockMvc.perform(post("/quiz-rooms/1/quizzes/99/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-58] 존재하지 않는 보기 번호는 400 QUIZ_OPTION_NOT_FOUND")
    void answer_invalidOption_returns400() throws Exception {
        given(answerService.answer(USER_ID, 1L, 11L, 3))
                .willThrow(new BusinessException(ErrorCode.QUIZ_OPTION_NOT_FOUND));

        mockMvc.perform(post("/quiz-rooms/1/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("존재하지 않는 보기 번호입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-59] 이미 답한 문항은 409 QUIZ_ALREADY_SUBMITTED")
    void answer_alreadySubmitted_returns409() throws Exception {
        given(answerService.answer(USER_ID, 1L, 11L, 2))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        mockMvc.perform(post("/quiz-rooms/1/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 제출한 퀴즈입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-2] 답 제출 본문에 userAccountId 를 보내도 서비스는 토큰의 계정으로만 호출된다")
    void answer_bodyUserAccountId_isIgnored() throws Exception {
        given(answerService.answer(eq(USER_ID), anyLong(), anyLong(), any())).willReturn(new AnswerResponse(1, 3, null));

        mockMvc.perform(post("/quiz-rooms/1/quizzes/11/answer").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"option\":0,\"userAccountId\":999}"))
                .andExpect(status().isOk());

        verify(answerService).answer(USER_ID, 1L, 11L, 0);
    }

    // ---- 추천 (QUIZ-CQR-70~77) ----

    @Test
    @DisplayName("[QUIZ-CQR-70] liked:true 는 200 {liked:true, likeCount} 이고 서비스에 true 를 넘긴다")
    void setLiked_true_returns200() throws Exception {
        given(likeService.setLiked(USER_ID, 1L, true)).willReturn(new QuizRoomLikeResponse(true, 1L));

        mockMvc.perform(put("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
    }

    @Test
    @DisplayName("[QUIZ-CQR-71] liked:false 는 200 {liked:false, likeCount:0}")
    void setLiked_false_returns200() throws Exception {
        given(likeService.setLiked(USER_ID, 1L, false)).willReturn(new QuizRoomLikeResponse(false, 0L));

        mockMvc.perform(put("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    @Test
    @DisplayName("[QUIZ-CQR-73] liked 누락 {} 은 400 data.liked, 서비스는 호출되지 않는다")
    void setLiked_missing_returns400() throws Exception {
        mockMvc.perform(put("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.liked").exists());
        verifyNoInteractions(likeService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-73] liked 가 boolean 이 아닌 \"yes\" 이면 400")
    void setLiked_nonBoolean_returns400() throws Exception {
        mockMvc.perform(put("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":\"yes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(likeService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-73] liked 가 null 이면 400")
    void setLiked_null_returns400() throws Exception {
        mockMvc.perform(put("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":null}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(likeService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-74] 없는 방 추천은 404")
    void setLiked_roomNotFound_returns404() throws Exception {
        given(likeService.setLiked(USER_ID, 999999L, true)).willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(put("/quiz-rooms/999999/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[QUIZ-CQR-70] 추천은 PUT 만 받는다 - POST 토글 경로는 405")
    void like_postToggle_isNotSupported() throws Exception {
        mockMvc.perform(post("/quiz-rooms/1/like").with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(likeService);
    }

    // ---- 구독 (QUIZ-CQR-80, 81, 87) ----

    @Test
    @DisplayName("[QUIZ-CQR-80] 구독은 200 이고 비동기 text/event-stream 응답을 연다")
    void subscribe_opensEventStream() throws Exception {
        given(subscriptionService.subscribe(1L, USER_ID)).willReturn(new SseEmitter(60_000L));

        MvcResult result = mockMvc.perform(get("/quiz-rooms/1/subscribe").with(authenticatedAs(USER_ID))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        verify(subscriptionService).subscribe(1L, USER_ID);
    }

    @Test
    @DisplayName("[QUIZ-CQR-81] 없는 방 구독은 스트림을 열지 않고 404 JSON 이다")
    void subscribe_roomNotFound_returns404Json() throws Exception {
        given(subscriptionService.subscribe(999999L, USER_ID))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(get("/quiz-rooms/999999/subscribe").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈방입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-81] EventSource 처럼 Accept: text/event-stream 만 보내도 없는 방 구독은 404 JSON 이다")
    void subscribe_roomNotFound_withEventStreamAccept_returns404Json() throws Exception {
        given(subscriptionService.subscribe(999999L, USER_ID))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ROOM_NOT_FOUND));

        mockMvc.perform(get("/quiz-rooms/999999/subscribe").with(authenticatedAs(USER_ID))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 퀴즈방입니다."));
    }

    @Test
    @DisplayName("[QUIZ-CQR-87] 퇴장은 200 ApiResponse.ok(null) 이고 서비스에 위임한다")
    void unsubscribe_returns200AndDelegates() throws Exception {
        mockMvc.perform(delete("/quiz-rooms/1/subscribe").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(subscriptionService).unsubscribe(1L, USER_ID);
    }

    @Test
    @DisplayName("[QUIZ-CQR-87] 구독 없이 없는 방을 퇴장해도 200 이다 (컨트롤러는 방 존재를 확인하지 않는다)")
    void unsubscribe_nonexistentRoom_returns200() throws Exception {
        mockMvc.perform(delete("/quiz-rooms/999999/subscribe").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(subscriptionService).unsubscribe(999999L, USER_ID);
        verifyNoInteractions(roomService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-87] 서비스가 예외를 던지면 퇴장 응답은 200 이 아니다 - 멱등은 서비스 책임임을 고정 (요구사항 미기재)")
    void unsubscribe_serviceFailure_isNotSwallowedByController() throws Exception {
        willThrow(new RuntimeException("boom")).given(subscriptionService).unsubscribe(1L, USER_ID);

        mockMvc.perform(delete("/quiz-rooms/1/subscribe").with(authenticatedAs(USER_ID)))
                .andExpect(status().isInternalServerError());
    }

    // ---- 응답 구조 보조 ----

    @Test
    @DisplayName("[QUIZ-CQR-45] 조회 엔드포인트는 서비스의 조회 메서드만 부른다 (생성·제출·추천 서비스는 닿지 않는다)")
    void readEndpoints_neverTouchWriteServices() throws Exception {
        given(roomService.getRoom(USER_ID, 1L)).willReturn(detail(1L));
        given(roomService.getQuizzes(1L)).willReturn(List.of());

        mockMvc.perform(get("/quiz-rooms/1").with(authenticatedAs(USER_ID))).andExpect(status().isOk());
        mockMvc.perform(get("/quiz-rooms/1/quizzes").with(authenticatedAs(USER_ID))).andExpect(status().isOk());

        verifyNoInteractions(answerService, likeService, subscriptionService);
    }
}
