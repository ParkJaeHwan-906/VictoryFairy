package com.skhynix.quiz.quiz.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.OptionResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse.InningResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionItemResponse;
import com.skhynix.quiz.quiz.service.QuizSubmitService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
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
 * {@code GET /quizzes/submissions}(지목한 경기의 이닝별 풀이 결산) 슬라이스 테스트.
 * {@code docs/requirements/quiz/quiz-submission-by-inning.md} 승인분(AC-SUB-*)에 대응한다.
 *
 * <p>서비스는 목이므로 이닝 열거·접기 규칙·요약 산식 등 실제 계산은
 * {@code QuizSubmitServiceTest}가 맡고, 여기서는 요청 파라미터 바인딩(400/필수)·검사 순서에 따른
 * 상태코드 매핑(404/403/200)·인증 배선·응답 JSON 구조(신설/삭제 필드 포함)만 고정한다.
 */
@WebMvcTest(QuizSubmissionController.class)
@ContextConfiguration(classes = QuizSubmissionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizSubmissionControllerHistoryTest {

    private static final Long USER_ID = 1L;
    private static final String GAME_ID = "20260812LGWO02026";

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

    private QuizSubmissionHistoryResponse emptyHistory() {
        return QuizSubmissionHistoryResponse.of(List.of());
    }

    // ---------- QUIZ-SUB-1: 인증 ----------

    @Test
    @DisplayName("[AC-SUB-1-1] 헤더 없이 조회하면 401과 인증 필요 메시지를 반환하고 서비스는 호출되지 않는다")
    void getSubmissions_withoutAuthentication_returns401AndNeverCallsService() throws Exception {
        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));

        verify(quizSubmitService, never()).getHistory(anyLong(), anyString());
    }

    // ---------- QUIZ-SUB-2: 계정 식별은 토큰뿐 ----------

    @Test
    @DisplayName("[AC-SUB-2-1] 요청에 userAccountId를 함께 보내도 무시되고 인증 토큰의 계정으로만 조회한다")
    void getSubmissions_extraUserAccountIdParam_isIgnoredAndUsesAuthenticatedPrincipal()
            throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(emptyHistory());

        mockMvc.perform(get("/quizzes/submissions")
                        .param("gameId", GAME_ID)
                        .param("userAccountId", "999")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizSubmitService).getHistory(USER_ID, GAME_ID);
    }

    // ---------- QUIZ-SUB-3: gameId 축 ----------

    @Test
    @DisplayName("[AC-SUB-3-1,3-3] gameId를 그대로 서비스에 넘긴다 — /today와 같은 naver_game_id 축이다")
    void getSubmissions_withGameId_passesThroughToService() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(emptyHistory());

        mockMvc.perform(get("/quizzes/submissions")
                        .param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk());

        verify(quizSubmitService).getHistory(USER_ID, GAME_ID);
    }

    // ---------- QUIZ-SUB-4: gameId 필수 ----------

    @Test
    @DisplayName("[AC-SUB-4-1,4-3] gameId 파라미터가 없으면 400이고 서비스는 호출되지 않는다")
    void getSubmissions_missingGameId_returns400AndNeverCallsService() throws Exception {
        mockMvc.perform(get("/quizzes/submissions").with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(quizSubmitService, never()).getHistory(anyLong(), anyString());
    }

    @Test
    @DisplayName("[AC-SUB-4-2,4-3] gameId가 빈 문자열이면 400이고 서비스는 호출되지 않는다")
    void getSubmissions_blankGameId_returns400AndNeverCallsService() throws Exception {
        mockMvc.perform(get("/quizzes/submissions").param("gameId", "")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(quizSubmitService, never()).getHistory(anyLong(), anyString());
    }

    // ---------- QUIZ-SUB-5 / 검사 순서(404→403→200) ----------

    @Test
    @DisplayName("[AC-SUB-5-1] 서비스가 GAME_NOT_FOUND를 던지면 404와 그 메시지를 반환한다")
    void getSubmissions_gameNotFound_returns404() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID))
                .willThrow(new BusinessException(ErrorCode.GAME_NOT_FOUND));

        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.GAME_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("[AC-SUB-36-1,37-1] 서비스가 GAME_NOT_STARTED를 던지면 403과 그 메시지를 반환한다 — "
            + "QUIZ_NOT_SERVABLE(출제 거절 문구)이 아니다")
    void getSubmissions_gameNotStarted_returns403WithDedicatedMessage() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID))
                .willThrow(new BusinessException(ErrorCode.GAME_NOT_STARTED));

        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.GAME_NOT_STARTED.getMessage()))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(ErrorCode.QUIZ_NOT_SERVABLE.getMessage())));
    }

    // ---------- QUIZ-SUB-7: page 폐지 ----------

    @Test
    @DisplayName("[AC-SUB-7-1,7-2] page 파라미터를 보내도 무시되고 응답에 PageResponse 구조가 없다")
    void getSubmissions_withPageParam_ignoredAndNoPageStructureInResponse() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(emptyHistory());

        mockMvc.perform(get("/quizzes/submissions")
                        .param("gameId", GAME_ID)
                        .param("page", "3")
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submissions").doesNotExist())
                .andExpect(jsonPath("$.data.page").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").doesNotExist());

        verify(quizSubmitService).getHistory(USER_ID, GAME_ID);
    }

    // ---------- QUIZ-SUB-14 / 39: 대상 행 0건 ----------

    @Test
    @DisplayName("[AC-SUB-14-1,14-2,14-3,39-4] 대상 행이 0건이면 200과 innings:[], summary 전부 0을 "
            + "반환한다(NaN·null이 아니다)")
    void getSubmissions_noRows_returns200WithEmptyInningsAndZeroSummary() throws Exception {
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(emptyHistory());

        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.total").value(0))
                .andExpect(jsonPath("$.data.summary.correctCount").value(0))
                .andExpect(jsonPath("$.data.summary.accuracy").value(0.0))
                .andExpect(jsonPath("$.data.summary.earnedPoint").value(0))
                .andExpect(jsonPath("$.data.innings").isArray())
                .andExpect(jsonPath("$.data.innings.length()").value(0));
    }

    // ---------- QUIZ-SUB-30/40/41: 응답 구조 ----------

    @Test
    @DisplayName("[AC-SUB-30-1,40-1,41-1] 성공 응답은 summary·innings 최상위 구조를 갖고, 이닝 원소는 "
            + "inning·summary·quizzes를 가지며, 항목은 options 배열을 포함한 신규 필드 집합을 갖는다")
    void getSubmissions_success_returnsFullResponseStructure() throws Exception {
        QuizSubmissionItemResponse item = new QuizSubmissionItemResponse(
                30L, "문제 지문", "O/X", "EASY",
                List.of(new OptionResponse(0, "O"), new OptionResponse(1, "X")),
                0, true, false, 0, 50L,
                LocalDateTime.of(2026, 8, 13, 19, 3, 11), false, 2L);
        InningResponse inning1 = InningResponse.of(1, List.of(item));
        InningResponse inning2 = InningResponse.of(2, List.of());
        QuizSubmissionHistoryResponse response =
                QuizSubmissionHistoryResponse.of(List.of(inning1, inning2));
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(response);

        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summary.correctCount").value(1))
                .andExpect(jsonPath("$.data.summary.total").value(1))
                .andExpect(jsonPath("$.data.summary.earnedPoint").value(50))
                .andExpect(jsonPath("$.data.innings.length()").value(2))
                .andExpect(jsonPath("$.data.innings[0].inning").value(1))
                .andExpect(jsonPath("$.data.innings[0].summary.correctCount").value(1))
                .andExpect(jsonPath("$.data.innings[0].summary.total").value(1))
                .andExpect(jsonPath("$.data.innings[0].quizzes.length()").value(1))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].quizId").value(30))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].question").value("문제 지문"))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].type").value("O/X"))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options.length()").value(2))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[0].no").value(0))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[0].text").value("O"))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[1].no").value(1))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[1].text").value("X"))
                // [AC-VOTEVIEW-29-1] 이닝별 결산의 보기 항목에는 voteCount가 붙지 않는다(투표 수 노출은
                // /today 하나뿐이라는 계약)
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].options[1].voteCount").doesNotExist())
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].myOption").value(0))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].correct").value(true))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].expired").value(false))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].answer").value(0))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].earnedPoint").value(50))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].liked").value(false))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].likeCount").value(2))
                // 2회는 기록이 없어도 빈 원소로 남는다(QUIZ-SUB-33) — quizzes:[] + 0/0
                .andExpect(jsonPath("$.data.innings[1].inning").value(2))
                .andExpect(jsonPath("$.data.innings[1].summary.total").value(0))
                .andExpect(jsonPath("$.data.innings[1].summary.accuracy").value(0.0))
                .andExpect(jsonPath("$.data.innings[1].quizzes.length()").value(0))
                // 삭제된 필드는 키 자체가 없다
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].quizDate").doesNotExist())
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].myOptionText").doesNotExist())
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].answerText").doesNotExist())
                // 종전 페이징 구조 부재
                .andExpect(jsonPath("$.data.submissions").doesNotExist());
    }

    @Test
    @DisplayName("[AC-SUB-42-1] 답 없는 항목은 myOption이 null이고 correct는 false, earnedPoint는 0이다")
    void getSubmissions_unansweredItem_hasNullMyOptionAndZeroEarnedPoint() throws Exception {
        QuizSubmissionItemResponse unanswered = new QuizSubmissionItemResponse(
                31L, "아직 안 푼 문제", "O/X", "EASY",
                List.of(new OptionResponse(0, "O"), new OptionResponse(1, "X")),
                null, false, true, 0, 0L,
                LocalDateTime.of(2026, 8, 13, 19, 0), false, 0L);
        QuizSubmissionHistoryResponse response =
                QuizSubmissionHistoryResponse.of(List.of(InningResponse.of(1, List.of(unanswered))));
        given(quizSubmitService.getHistory(USER_ID, GAME_ID)).willReturn(response);

        mockMvc.perform(get("/quizzes/submissions").param("gameId", GAME_ID)
                        .with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].myOption")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].correct").value(false))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].expired").value(true))
                .andExpect(jsonPath("$.data.innings[0].quizzes[0].earnedPoint").value(0));
    }
}
