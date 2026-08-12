package com.skhynix.quiz.quiz.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.chat.dto.PageResponse;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.service.QuizSubmitService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code GET /quizzes/submissions}(풀이 이력) 슬라이스 테스트. 서비스는 목이므로 페이지 크기 20·최신순
 * 정렬·요약 계산은 {@code QuizSubmitServiceTest}가 맡고, 여기서는 응답 구조(summary + submissions)와
 * page 파라미터 바인딩·인증 배선만 고정한다.
 */
@WebMvcTest(QuizSubmissionController.class)
@ContextConfiguration(classes = QuizSubmissionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizSubmissionControllerHistoryTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizSubmitService quizSubmitService;

    // SecurityConfig의 JwtAuthenticationFilter 생성에 필요 — 테스트 자체는 사용하지 않는다
    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }

    private QuizSubmissionHistoryResponse historyOf(List<QuizSubmissionItemResponse> items,
            long total, long correctCount, double accuracy) {
        return new QuizSubmissionHistoryResponse(
                new QuizSubmissionHistoryResponse.Summary(total, correctCount, accuracy),
                new PageResponse<>(items, 0, 20, total, 1, false));
    }

    @Test
    @DisplayName("[AC-LIKE-34-1] 이력을 조회하면 200과 summary + submissions 페이지 구조를 그대로 반환하고 "
            + "각 항목에 liked·likeCount가 실린다")
    void getSubmissions_returns200WithSummaryAndPage() throws Exception {
        QuizSubmissionItemResponse item = new QuizSubmissionItemResponse(
                10L, "문제 지문", "객관식", "EASY", LocalDate.of(2026, 8, 8),
                1, "오답 보기", false, 0, "정답 보기", 0L,
                LocalDateTime.of(2026, 8, 8, 9, 30), true, 5L);
        given(quizSubmitService.getHistory(USER_ID, 0)).willReturn(historyOf(List.of(item), 4L, 2L, 0.5));

        mockMvc.perform(get("/quizzes/submissions")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.total").value(4))
                .andExpect(jsonPath("$.data.summary.correctCount").value(2))
                .andExpect(jsonPath("$.data.summary.accuracy").value(0.5))
                .andExpect(jsonPath("$.data.submissions.content.length()").value(1))
                .andExpect(jsonPath("$.data.submissions.content[0].quizId").value(10))
                .andExpect(jsonPath("$.data.submissions.content[0].question").value("문제 지문"))
                .andExpect(jsonPath("$.data.submissions.content[0].type").value("객관식"))
                .andExpect(jsonPath("$.data.submissions.content[0].myOption").value(1))
                .andExpect(jsonPath("$.data.submissions.content[0].myOptionText").value("오답 보기"))
                .andExpect(jsonPath("$.data.submissions.content[0].correct").value(false))
                .andExpect(jsonPath("$.data.submissions.content[0].answer").value(0))
                .andExpect(jsonPath("$.data.submissions.content[0].answerText").value("정답 보기"))
                .andExpect(jsonPath("$.data.submissions.content[0].earnedPoint").value(0))
                .andExpect(jsonPath("$.data.submissions.content[0].liked").value(true))
                .andExpect(jsonPath("$.data.submissions.content[0].likeCount").value(5))
                // [AC-INN-25-2] 이력 응답도 이닝 기능과 무관 — 항목에 inning 키가 없다
                .andExpect(jsonPath("$.data.submissions.content[0].inning").doesNotExist())
                .andExpect(jsonPath("$.data.submissions.size").value(20))
                .andExpect(jsonPath("$.data.submissions.hasNext").value(false));

        verify(quizSubmitService).getHistory(USER_ID, 0);
    }

    @Test
    @DisplayName("page 파라미터를 생략하면 기본값 0으로 서비스에 위임한다")
    void getSubmissions_withoutPageParam_defaultsToPageZero() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, 0)).willReturn(historyOf(List.of(), 0L, 0L, 0.0));

        mockMvc.perform(get("/quizzes/submissions")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizSubmitService).getHistory(USER_ID, 0);
    }

    @Test
    @DisplayName("page 파라미터를 지정하면 그 값 그대로 서비스에 위임한다")
    void getSubmissions_withExplicitPageParam_passesItThrough() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, 3)).willReturn(historyOf(List.of(), 0L, 0L, 0.0));

        mockMvc.perform(get("/quizzes/submissions")
                        .param("page", "3")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizSubmitService).getHistory(USER_ID, 3);
    }

    @Test
    @DisplayName("인증 없이 이력을 조회하면 401을 반환하고 서비스는 호출되지 않는다")
    void getSubmissions_withoutAuthentication_returns401AndNeverCallsService() throws Exception {
        mockMvc.perform(get("/quizzes/submissions"))
                .andExpect(status().isUnauthorized());

        verify(quizSubmitService, never()).getHistory(anyLong(), anyInt());
    }
}
