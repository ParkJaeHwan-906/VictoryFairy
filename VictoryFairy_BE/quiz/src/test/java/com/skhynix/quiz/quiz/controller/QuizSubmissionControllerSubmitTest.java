package com.skhynix.quiz.quiz.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.QuizSubmitRequest;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import com.skhynix.quiz.quiz.service.QuizSubmitService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /quizzes/{quizId}/submit} 슬라이스 테스트. {@code QuizSubmitService}는 목이므로 채점·적립
 * 로직은 {@code QuizSubmitServiceTest}가 맡고, 여기서는 상태 코드·JSON 매핑·인증 배선·{@code @Valid}
 * 검증 배선만 고정한다. MockMvc 는 context-path(/rt)를 적용하지 않으므로 {@code /quizzes/...}로 요청한다.
 *
 * <p>인증 주입은 chat/quiz 슬라이스와 동일 — {@code JwtAuthenticationFilter}가 만드는 {@code Long}
 * principal 을 {@code SecurityMockMvcRequestPostProcessors.authentication}으로 재현한다.
 */
@WebMvcTest(QuizSubmissionController.class)
@ContextConfiguration(classes = QuizSubmissionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizSubmissionControllerSubmitTest {

    private static final Long USER_ID = 1L;
    private static final Long QUIZ_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QuizSubmitService quizSubmitService;

    // SecurityConfig의 JwtAuthenticationFilter 생성에 필요 — 테스트 자체는 사용하지 않는다
    @MockitoBean
    private UserAccountRepository userAccountRepository;

    private static RequestPostProcessor authenticatedAs(Long userAccountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(userAccountId, null, List.of()));
    }

    private String requestJson(Integer option) {
        return objectMapper.writeValueAsString(new QuizSubmitRequest(option));
    }

    @Test
    @DisplayName("유효한 보기 번호로 제출하면 200과 채점 결과 JSON을 반환하고 서비스에 위임한다")
    void submit_validOption_returns200WithGradingResult() throws Exception {
        given(quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .willReturn(new QuizSubmitResponse(true, 0, 0, 10L, 110L));

        mockMvc.perform(post("/quizzes/{quizId}/submit", QUIZ_ID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.correct").value(true))
                .andExpect(jsonPath("$.data.answer").value(0))
                .andExpect(jsonPath("$.data.myOption").value(0))
                .andExpect(jsonPath("$.data.earnedPoint").value(10))
                .andExpect(jsonPath("$.data.totalPoint").value(110))
                // [AC-INN-25-1] 제출 응답 필드 집합은 이닝 기능 도입 후에도 바뀌지 않는다 — inning 키가 없다
                .andExpect(jsonPath("$.data.inning").doesNotExist())
                // [AC-VOTEVIEW-28-1] 제출 직후 응답에도 분포를 돌려주지 않는다 — voteCount 키가 없다
                .andExpect(jsonPath("$.data.voteCount").doesNotExist());

        verify(quizSubmitService).submit(USER_ID, QUIZ_ID, 0);
    }

    @Test
    @DisplayName("인증 없이 제출하면 401을 반환하고 서비스는 호출되지 않는다")
    void submit_withoutAuthentication_returns401AndNeverCallsService() throws Exception {
        mockMvc.perform(post("/quizzes/{quizId}/submit", QUIZ_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(0)))
                .andExpect(status().isUnauthorized());

        verify(quizSubmitService, never()).submit(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("option이 누락되면 400과 필드 검증 메시지를 반환하고 서비스는 호출되지 않는다")
    void submit_missingOption_returns400WithValidationMessage() throws Exception {
        mockMvc.perform(post("/quizzes/{quizId}/submit", QUIZ_ID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
                // 필드별 메시지 본문은 로케일 의존이라 존재만 고정한다
                .andExpect(jsonPath("$.data.option").exists());

        verify(quizSubmitService, never()).submit(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("서비스가 QUIZ_ALREADY_SUBMITTED를 던지면 409와 규약 메시지로 변환된다")
    void submit_alreadySubmitted_returns409() throws Exception {
        given(quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .willThrow(new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED));

        mockMvc.perform(post("/quizzes/{quizId}/submit", QUIZ_ID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_ALREADY_SUBMITTED.getMessage()));
    }

    @Test
    @DisplayName("[AC-INN-29,30-1,30-2] 서비스가 QUIZ_SUBMIT_NOT_ALLOWED를 던지면 403과 규약 메시지로 "
            + "변환된다(success:false, data:null, message:<문구>)")
    void submit_ticketNotFound_returns403() throws Exception {
        given(quizSubmitService.submit(USER_ID, QUIZ_ID, 0))
                .willThrow(new BusinessException(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED));

        mockMvc.perform(post("/quizzes/{quizId}/submit", QUIZ_ID)
                        .with(authenticatedAs(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_SUBMIT_NOT_ALLOWED.getMessage()));
    }
}
