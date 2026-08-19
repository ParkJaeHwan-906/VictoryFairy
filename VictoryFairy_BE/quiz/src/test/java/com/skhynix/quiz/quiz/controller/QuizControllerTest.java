package com.skhynix.quiz.quiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.OptionResponse;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse.TodayOptionResponse;
import com.skhynix.quiz.quiz.service.QuizLikeService;
import com.skhynix.quiz.quiz.service.QuizService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code QuizController} 슬라이스 테스트. {@code QuizService}는 목으로 대체해 상태 코드·JSON 매핑·
 * 인증 배선만 검증한다. MockMvc 는 context-path(/rt)를 적용하지 않으므로 {@code /quizzes/today}로
 * 요청한다(프로덕션 노출 경로는 {@code /rt/quizzes/today}).
 *
 * <p>인증 주입은 chat 슬라이스의 {@code ChatControllerTestSupport}와 동일한 방식이다 —
 * {@code @WithMockUser}는 principal 을 {@code User}/문자열로 만들어 실제 필터가 만드는
 * {@code Long} principal 과 형태가 다르므로, {@code SecurityMockMvcRequestPostProcessors.authentication}
 * 으로 동일한 {@link UsernamePasswordAuthenticationToken}을 직접 주입한다.
 */
@WebMvcTest(QuizController.class)
@ContextConfiguration(classes = QuizController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizControllerTest {

    private static final Long USER_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final String GAME_ID = "20260807HHKT02026";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizService quizService;

    @MockitoBean
    private QuizLikeService quizLikeService;

    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }

    // ---------- GET /quizzes/today ----------

    @Test
    @DisplayName("인증 없이 오늘의 퀴즈를 요청하면 401을 반환한다")
    void getTodayQuizzes_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/quizzes/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));
    }

    @Test
    @DisplayName("[AC-INN-110-1,2,3] gameId 파라미터 없이 요청하면 400과 공통 ApiResponse 래퍼로 응답하고 "
            + "서비스는 호출되지 않는다(필수 파라미터 누락을 기본값으로 흡수하지 않는다)")
    void getTodayQuizzes_missingGameIdParam_returns400WithApiResponseWrapper() throws Exception {
        mockMvc.perform(get("/quizzes/today").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(quizService);
    }

    @Test
    @DisplayName("[AC-INN-97-1,97-2,97-3] 서비스가 QUIZ_NOT_SERVABLE을 던지면 403과 그 메시지를 반환한다")
    void getTodayQuizzes_notServable_returns403() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .willThrow(new BusinessException(ErrorCode.QUIZ_NOT_SERVABLE));

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_NOT_SERVABLE.getMessage()));
    }

    @Test
    @DisplayName("[AC-INN-97-1,97-2,97-3] 서비스가 QUIZ_ALREADY_SERVED_IN_INNING을 던지면 409와 그 "
            + "메시지를 반환한다")
    void getTodayQuizzes_alreadyServedInInning_returns409() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ALREADY_SERVED_IN_INNING));

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.QUIZ_ALREADY_SERVED_IN_INNING.getMessage()));
    }

    @Test
    @DisplayName("[AC-INN-105-1] gameId 값은 그대로 서비스에 문자열로 전달된다(naver_game_id, 내부 PK "
            + "아님)")
    void getTodayQuizzes_gameIdParam_passedToServiceAsIs() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizService).getTodayQuizzes(USER_ID, GAME_ID, false);
    }

    @Test
    @DisplayName("[AC-INN-111-1] inning은 요청 파라미터로 받지 않는다 — 알 수 없는 쿼리 파라미터로 "
            + "보내도 무시되고 200이다(서버가 읽어 채우는 값이라 입력 경로가 없다는 것의 방증)")
    void getTodayQuizzes_strayInningParam_isIgnored() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID).param("inning", "5")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizService).getTodayQuizzes(USER_ID, GAME_ID, false);
    }

    @Test
    @DisplayName("[AC-LIKE-31-1,2] 인증 사용자가 요청하면 200과 문제 목록을 반환하고, 응답 JSON 어디에도 "
            + "answer·liked·likeCount 키가 없으며 QuizLikeService는 전혀 호출되지 않는다"
            + "(정답 미노출 계약 + /today 좋아요 집계 비용 없음)")
    void getTodayQuizzes_authenticated_returns200WithoutAnswerKey() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false)).willReturn(List.of(
                new QuizResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0, true,
                        List.of(new TodayOptionResponse(0, "LG", 3L),
                                new TodayOptionResponse(1, "한화", 0L),
                                new TodayOptionResponse(2, "삼성", 0L),
                                new TodayOptionResponse(3, "KT", 0L))),
                new QuizResponse(2L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0, false,
                        List.of(new TodayOptionResponse(0, "O", 0L),
                                new TodayOptionResponse(1, "X", 0L)))));

        MvcResult result = mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].type").value("객관식"))
                .andExpect(jsonPath("$.data[0].question").value("2025 정규시즌 우승 구단은?"))
                .andExpect(jsonPath("$.data[0].difficulty").value("MEDIUM"))
                .andExpect(jsonPath("$.data[0].point").value(30.0))
                .andExpect(jsonPath("$.data[0].preferred").value(true))
                .andExpect(jsonPath("$.data[0].options.length()").value(4))
                .andExpect(jsonPath("$.data[0].options[0].no").value(0))
                .andExpect(jsonPath("$.data[0].options[0].text").value("LG"))
                // [AC-VOTEVIEW-1-1,1-2,2-1] voteCount는 값이 0이어도 키가 생략되지 않고, 문자열이 아닌
                // JSON 정수로 실린다
                .andExpect(jsonPath("$.data[0].options[0].voteCount").value(3))
                .andExpect(jsonPath("$.data[0].options[1].voteCount").value(0))
                .andExpect(jsonPath("$.data[1].type").value("O/X"))
                .andExpect(jsonPath("$.data[1].preferred").value(false))
                .andExpect(jsonPath("$.data[1].options.length()").value(2))
                .andExpect(jsonPath("$.data[1].options[0].voteCount").value(0))
                .andExpect(jsonPath("$.data[1].options[1].voteCount").value(0))
                .andExpect(jsonPath("$.data[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data[1].answer").doesNotExist())
                .andExpect(jsonPath("$.data[0].liked").doesNotExist())
                .andExpect(jsonPath("$.data[0].likeCount").doesNotExist())
                .andExpect(jsonPath("$.data[1].liked").doesNotExist())
                .andExpect(jsonPath("$.data[1].likeCount").doesNotExist())
                // [AC-VOTEVIEW-5-1] 이번 변경으로 추가된 필드는 voteCount 하나뿐 — 총합·비율 필드가 없다
                .andExpect(jsonPath("$.data[0].totalVotes").doesNotExist())
                .andExpect(jsonPath("$.data[0].voteRatio").doesNotExist())
                // [AC-INN-15-1] /today 응답 필드 집합은 이닝 기능 도입 후에도 바뀌지 않는다 — inning 키가 없다
                .andExpect(jsonPath("$.data[0].inning").doesNotExist())
                .andExpect(jsonPath("$.data[1].inning").doesNotExist())
                .andReturn();

        // "answer" 라는 문자열 자체가 응답 본문 어디에도 없어야 한다(isAnswer·answerRate 류까지 차단)
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("answer");
        // "voteCount":3 형태로 실려야 한다(문자열 "3"이 아니다) — AC-VOTEVIEW-2-1
        assertThat(body).contains("\"voteCount\":3");
        assertThat(body).doesNotContain("\"voteCount\":\"3\"");
        // AC-LIKE-31-2: /today 처리 중 QuizLikeService 를 아예 호출하지 않는다(집계 쿼리도 발생 안 함)
        verifyNoInteractions(quizLikeService);
    }

    @Test
    @DisplayName("오늘 출제분이 없으면 200과 빈 배열을 반환한다(404·에러 아님)")
    void getTodayQuizzes_emptyDay_returns200WithEmptyArray() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, false)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(content().json("{\"success\":true,\"data\":[],\"message\":null}", true));
    }

    @Test
    @DisplayName("preferredOnly=true 쿼리 파라미터가 서비스까지 true로 바인딩된다(기본값은 false)")
    void getTodayQuizzes_preferredOnlyParam_bindsToService() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, GAME_ID, true)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").param("gameId", GAME_ID)
                        .param("preferredOnly", "true")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizService).getTodayQuizzes(USER_ID, GAME_ID, true);
    }

    // ---------- GET /quizzes/{quizId} ----------

    @Test
    @DisplayName("[AC-LIKE-33-1] 미제출 문제 상세는 200이되, 응답 본문에 \"answer\" 문자열 자체가 없고 "
            + "liked·likeCount 키도 부재다(NON_NULL 직렬화 — 키가 null 값으로도 실리면 안 된다)")
    void getQuiz_notSubmitted_returns200WithoutAnswerKeyAnywhere() throws Exception {
        given(quizService.getQuiz(USER_ID, 1L)).willReturn(QuizDetailResponseFixture.unsubmitted());

        MvcResult result = mockMvc.perform(get("/quizzes/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.type").value("객관식"))
                .andExpect(jsonPath("$.data.quizDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.data.submitted").value(false))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.options.length()").value(2))
                .andExpect(jsonPath("$.data.myOption").doesNotExist())
                .andExpect(jsonPath("$.data.correct").doesNotExist())
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andExpect(jsonPath("$.data.liked").doesNotExist())
                .andExpect(jsonPath("$.data.likeCount").doesNotExist())
                // [AC-INN-25-2] 상세 응답도 이닝 기능과 무관 — inning 키가 없다
                .andExpect(jsonPath("$.data.inning").doesNotExist())
                // [AC-VOTEVIEW-27-1] 상세 응답 필드 집합은 투표 수 노출과 무관 — voteCount 계열 키가 없다
                .andExpect(jsonPath("$.data.options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.data.options[1].voteCount").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("answer");
        assertThat(body).doesNotContain("voteCount");
    }

    @Test
    @DisplayName("[AC-LIKE-32-1] 제출한 문제 상세는 myOption·correct·answer·liked·likeCount를 함께 싣는다")
    void getQuiz_submitted_returns200WithAnswerFields() throws Exception {
        given(quizService.getQuiz(USER_ID, 1L)).willReturn(QuizDetailResponseFixture.submitted());

        mockMvc.perform(get("/quizzes/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitted").value(true))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.myOption").value(1))
                .andExpect(jsonPath("$.data.correct").value(false))
                .andExpect(jsonPath("$.data.answer").value(0))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(5))
                // [AC-INN-25-2] 제출한 문제 상세도 마찬가지로 inning 키가 없다
                .andExpect(jsonPath("$.data.inning").doesNotExist())
                // [AC-VOTEVIEW-27-1] 제출한 문제 상세도 voteCount 계열 키가 없다
                .andExpect(jsonPath("$.data.options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.data.options[1].voteCount").doesNotExist());
    }

    @Test
    @DisplayName("[AC-INN-79-3] 미답이고 시한이 지난 문제 상세는 submitted=false·expired=true다"
            + "(제출하면 403이 되는 상태 — FE가 (submitted,expired) 조합으로 읽는 세 상태 중 하나)")
    void getQuiz_unansweredAndExpired_returns200WithExpiredTrue() throws Exception {
        given(quizService.getQuiz(USER_ID, 1L))
                .willReturn(QuizDetailResponseFixture.unsubmittedExpired());

        mockMvc.perform(get("/quizzes/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitted").value(false))
                .andExpect(jsonPath("$.data.expired").value(true))
                .andExpect(jsonPath("$.data.myOption").doesNotExist())
                .andExpect(jsonPath("$.data.correct").doesNotExist())
                .andExpect(jsonPath("$.data.answer").doesNotExist());
    }

    @Test
    @DisplayName("없는(또는 미편성) 문제 상세는 404와 QUIZ_NOT_FOUND 메시지를 반환한다")
    void getQuiz_missingQuiz_returns404() throws Exception {
        given(quizService.getQuiz(USER_ID, 99L))
                .willThrow(new BusinessException(ErrorCode.QUIZ_NOT_FOUND));

        mockMvc.perform(get("/quizzes/99").with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("인증 없이 문제 상세를 요청하면 401을 반환한다")
    void getQuiz_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/quizzes/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));
    }

    /** 상세 응답 픽스처 — 미제출/제출의 차이(마지막 세 필드)만 다르게 재사용한다. */
    private static class QuizDetailResponseFixture {

        private static final List<OptionResponse> OPTIONS = List.of(
                new OptionResponse(0, "LG"),
                new OptionResponse(1, "한화"));

        static QuizDetailResponse unsubmitted() {
            return new QuizDetailResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM",
                    30.0, TODAY, OPTIONS, false, false, null, null, null, null, null);
        }

        static QuizDetailResponse unsubmittedExpired() {
            return new QuizDetailResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM",
                    30.0, TODAY, OPTIONS, false, true, null, null, null, null, null);
        }

        static QuizDetailResponse submitted() {
            return new QuizDetailResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM",
                    30.0, TODAY, OPTIONS, true, false, 1, false, 0, true, 5L);
        }
    }
}
