package com.skhynix.quiz.quiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizService quizService;

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
    @DisplayName("인증 사용자가 요청하면 200과 문제 목록을 반환하고, 응답 JSON 어디에도 "
            + "answer 키가 없다(정답 미노출 계약)")
    void getTodayQuizzes_authenticated_returns200WithoutAnswerKey() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, false)).willReturn(List.of(
                new QuizResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0, true,
                        List.of(new QuizResponse.OptionResponse(0, "LG"),
                                new QuizResponse.OptionResponse(1, "한화"),
                                new QuizResponse.OptionResponse(2, "삼성"),
                                new QuizResponse.OptionResponse(3, "KT"))),
                new QuizResponse(2L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0, false,
                        List.of(new QuizResponse.OptionResponse(0, "O"),
                                new QuizResponse.OptionResponse(1, "X")))));

        MvcResult result = mockMvc.perform(get("/quizzes/today").with(authenticatedAs(USER_ID)))
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
                .andExpect(jsonPath("$.data[1].type").value("O/X"))
                .andExpect(jsonPath("$.data[1].preferred").value(false))
                .andExpect(jsonPath("$.data[1].options.length()").value(2))
                .andExpect(jsonPath("$.data[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data[1].answer").doesNotExist())
                .andReturn();

        // "answer" 라는 문자열 자체가 응답 본문 어디에도 없어야 한다(isAnswer·answerRate 류까지 차단)
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("answer");
    }

    @Test
    @DisplayName("오늘 출제분이 없으면 200과 빈 배열을 반환한다(404·에러 아님)")
    void getTodayQuizzes_emptyDay_returns200WithEmptyArray() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, false)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(content().json("{\"success\":true,\"data\":[],\"message\":null}", true));
    }

    @Test
    @DisplayName("preferredOnly=true 쿼리 파라미터가 서비스까지 true로 바인딩된다(기본값은 false)")
    void getTodayQuizzes_preferredOnlyParam_bindsToService() throws Exception {
        given(quizService.getTodayQuizzes(USER_ID, true)).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").param("preferredOnly", "true")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizService).getTodayQuizzes(USER_ID, true);
    }

    // ---------- GET /quizzes/{quizId} ----------

    @Test
    @DisplayName("미제출 문제 상세는 200이되, 응답 본문에 \"answer\" 문자열 자체가 없다"
            + "(NON_NULL 직렬화 — 키가 null 값으로도 실리면 안 된다)")
    void getQuiz_notSubmitted_returns200WithoutAnswerKeyAnywhere() throws Exception {
        given(quizService.getQuiz(USER_ID, 1L)).willReturn(QuizDetailResponseFixture.unsubmitted());

        MvcResult result = mockMvc.perform(get("/quizzes/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.type").value("객관식"))
                .andExpect(jsonPath("$.data.quizDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.data.submitted").value(false))
                .andExpect(jsonPath("$.data.options.length()").value(2))
                .andExpect(jsonPath("$.data.myOption").doesNotExist())
                .andExpect(jsonPath("$.data.correct").doesNotExist())
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("answer");
    }

    @Test
    @DisplayName("제출한 문제 상세는 myOption·correct·answer를 함께 싣는다")
    void getQuiz_submitted_returns200WithAnswerFields() throws Exception {
        given(quizService.getQuiz(USER_ID, 1L)).willReturn(QuizDetailResponseFixture.submitted());

        mockMvc.perform(get("/quizzes/1").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitted").value(true))
                .andExpect(jsonPath("$.data.myOption").value(1))
                .andExpect(jsonPath("$.data.correct").value(false))
                .andExpect(jsonPath("$.data.answer").value(0));
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

        private static final List<QuizResponse.OptionResponse> OPTIONS = List.of(
                new QuizResponse.OptionResponse(0, "LG"),
                new QuizResponse.OptionResponse(1, "한화"));

        static QuizDetailResponse unsubmitted() {
            return new QuizDetailResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM",
                    30.0, TODAY, OPTIONS, false, null, null, null);
        }

        static QuizDetailResponse submitted() {
            return new QuizDetailResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM",
                    30.0, TODAY, OPTIONS, true, 1, false, 0);
        }
    }
}
