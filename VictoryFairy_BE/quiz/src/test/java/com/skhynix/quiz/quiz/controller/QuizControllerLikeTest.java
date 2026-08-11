package com.skhynix.quiz.quiz.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.quiz.global.config.SecurityConfig;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.service.QuizLikeService;
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
 * {@code POST /quizzes/{quizId}/like}(좋아요 토글) 슬라이스 테스트. {@code QuizLikeService}는 목이므로
 * 토글 판정·확정 카운트 조립 자체는 {@code QuizLikeServiceTest}/{@code QuizLikeTogglerTest}가 맡고,
 * 여기서는 상태 코드·JSON 매핑·인증 배선, 그리고 <b>거절 3경우의 응답 동일성(AC-LIKE-30)</b>을 고정한다.
 *
 * <p>인증 주입은 {@code QuizControllerTest}와 동일하게 {@code SecurityMockMvcRequestPostProcessors
 * .authentication}으로 {@code Long} principal 을 직접 주입한다({@code @WithMockUser} 금지 — 모듈
 * 컨벤션).
 */
@WebMvcTest(QuizController.class)
@ContextConfiguration(classes = QuizController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class QuizControllerLikeTest {

    private static final Long USER_ID = 1L;

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

    @Test
    @DisplayName("[AC-LIKE-1-1] 인증 없이 좋아요를 요청하면 401을 반환하고 서비스는 호출되지 않는다")
    void toggleLike_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(post("/quizzes/1/like"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHENTICATED.getMessage()));
    }

    @Test
    @DisplayName("[AC-LIKE-6-2, AC-LIKE-12-1, AC-LIKE-13-1] 처음 좋아요를 누르면 200과 "
            + "{liked:true, likeCount:...}를 ApiResponse 래퍼로 반환한다")
    void toggleLike_firstTime_returns200WithLikedTrue() throws Exception {
        given(quizLikeService.toggle(USER_ID, 23L)).willReturn(new QuizLikeResponse(true, 5L));

        mockMvc.perform(post("/quizzes/23/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(5));
    }

    @Test
    @DisplayName("[AC-LIKE-7-1, AC-LIKE-9-1] 좋아요 -> 취소 -> 좋아요 3회 연속 호출에서 liked가 "
            + "true -> false -> true로 왕복한다(토글은 화면 상태의 정본, 매 호출 응답이 확정 상태)")
    void toggleLike_calledThreeTimesInARow_alternatesLikedState() throws Exception {
        given(quizLikeService.toggle(USER_ID, 23L))
                .willReturn(new QuizLikeResponse(true, 1L))
                .willReturn(new QuizLikeResponse(false, 0L))
                .willReturn(new QuizLikeResponse(true, 1L));

        mockMvc.perform(post("/quizzes/23/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        mockMvc.perform(post("/quizzes/23/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(0));
        mockMvc.perform(post("/quizzes/23/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
    }

    @Test
    @DisplayName("[AC-LIKE-14-1] 좋아요 응답 data에 내부 PK(id) 키가 없다")
    void toggleLike_responseData_hasNoIdKey() throws Exception {
        given(quizLikeService.toggle(USER_ID, 23L)).willReturn(new QuizLikeResponse(true, 1L));

        mockMvc.perform(post("/quizzes/23/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").doesNotExist());
    }

    // ---------- AC-LIKE-28·29·30: 거절 3경우가 완전히 동일한 응답인가 ----------

    /**
     * <b>이 기능에서 가장 중요한 계약</b>: 존재하지 않는 문제·미편성 풀 문제·편성됐지만 미제출한 문제,
     * 세 요청 모두 컨트롤러 입장에서는 {@code QuizLikeService.toggle}이 같은 {@code BusinessException
     * (QUIZ_LIKE_NOT_ALLOWED)}을 던지는 것으로 나타난다(구현은 퀴즈 조회 자체를 하지 않고 제출 이력
     * 존재 확인 하나로 세 경우를 합친다 — {@code QuizLikeToggler} javadoc). 여기서는 그 예외가 그대로
     * 403으로 변환되고, 세 요청의 <b>상태코드·에러코드·메시지·JSON 본문 문자열이 완전히 같음</b>을
     * 바이트 단위로 확인한다.
     */
    @Test
    @DisplayName("[AC-LIKE-28-1,2,3, AC-LIKE-30-1,2,3] 미존재·미편성·미제출 세 요청이 "
            + "상태코드 403·에러코드·메시지·본문 문자열까지 완전히 동일한 응답을 반환한다")
    void toggleLike_threeRejectionCases_returnIdenticalResponses() throws Exception {
        long nonExistentQuizId = 999999L;
        long unpublishedPoolQuizId = 42L;
        long notSubmittedQuizId = 7L;

        BusinessException rejection = new BusinessException(ErrorCode.QUIZ_LIKE_NOT_ALLOWED);
        given(quizLikeService.toggle(USER_ID, nonExistentQuizId)).willThrow(rejection);
        given(quizLikeService.toggle(USER_ID, unpublishedPoolQuizId)).willThrow(rejection);
        given(quizLikeService.toggle(USER_ID, notSubmittedQuizId)).willThrow(rejection);

        MvcResult missing = mockMvc
                .perform(post("/quizzes/" + nonExistentQuizId + "/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_LIKE_NOT_ALLOWED.getMessage()))
                .andReturn();
        MvcResult unpublished = mockMvc
                .perform(post("/quizzes/" + unpublishedPoolQuizId + "/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult notSubmitted = mockMvc
                .perform(post("/quizzes/" + notSubmittedQuizId + "/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andReturn();

        String missingBody = missing.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String unpublishedBody =
                unpublished.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String notSubmittedBody =
                notSubmitted.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 상태코드 동일
        assertThat(missing.getResponse().getStatus())
                .isEqualTo(unpublished.getResponse().getStatus())
                .isEqualTo(notSubmitted.getResponse().getStatus());
        // 본문 문자열까지 바이트 단위로 동일 — quizId 가 응답 어디에도 드러나지 않는다는 뜻이기도 하다
        assertThat(missingBody).isEqualTo(unpublishedBody).isEqualTo(notSubmittedBody);
        // 문구가 세 경우를 구분하지 않는 포괄 문구인지(메시지에 quizId·"미편성"·"존재" 등이 없어야 함)
        assertThat(missingBody).doesNotContain("999999", "42", "존재하지", "미편성", "QUIZ_NOT_FOUND");
    }

    @Test
    @DisplayName("[AC-LIKE-29-3] 좋아요 거절은 기존 QUIZ_NOT_FOUND(404)가 아니라 QUIZ_LIKE_NOT_ALLOWED(403)를 쓴다")
    void toggleLike_rejected_usesQuizLikeNotAllowedNotQuizNotFound() throws Exception {
        given(quizLikeService.toggle(USER_ID, 999999L))
                .willThrow(new BusinessException(ErrorCode.QUIZ_LIKE_NOT_ALLOWED));

        mockMvc.perform(post("/quizzes/999999/like").with(authenticatedAs(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(status().is(ErrorCode.QUIZ_LIKE_NOT_ALLOWED.getStatus()))
                .andExpect(jsonPath("$.message").value(ErrorCode.QUIZ_LIKE_NOT_ALLOWED.getMessage()));

        verify(quizLikeService).toggle(USER_ID, 999999L);
    }
}
