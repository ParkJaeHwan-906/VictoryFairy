package com.skhynix.quiz.quiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.service.QuizService;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
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
        given(quizService.getTodayQuizzes()).willReturn(List.of(
                new QuizResponse(1L, "객관식", "2025 정규시즌 우승 구단은?", "MEDIUM", 30.0,
                        List.of(new QuizResponse.OptionResponse(0, "LG"),
                                new QuizResponse.OptionResponse(1, "한화"),
                                new QuizResponse.OptionResponse(2, "삼성"),
                                new QuizResponse.OptionResponse(3, "KT"))),
                new QuizResponse(2L, "O/X", "문동주는 한화 소속이다?", "EASY", 10.0,
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
                .andExpect(jsonPath("$.data[0].options.length()").value(4))
                .andExpect(jsonPath("$.data[0].options[0].no").value(0))
                .andExpect(jsonPath("$.data[0].options[0].text").value("LG"))
                .andExpect(jsonPath("$.data[1].type").value("O/X"))
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
        given(quizService.getTodayQuizzes()).willReturn(List.of());

        mockMvc.perform(get("/quizzes/today").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(content().json("{\"success\":true,\"data\":[],\"message\":null}", true));
    }
}
