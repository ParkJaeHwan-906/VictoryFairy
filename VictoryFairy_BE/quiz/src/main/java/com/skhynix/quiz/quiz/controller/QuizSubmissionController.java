package com.skhynix.quiz.quiz.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmissionHistoryResponse;
import com.skhynix.quiz.quiz.dto.QuizSubmitRequest;
import com.skhynix.quiz.quiz.dto.QuizSubmitResponse;
import com.skhynix.quiz.quiz.service.QuizSubmitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 제출·풀이 이력 엔드포인트. 인증 필수(quiz {@code SecurityConfig})이며 principal 은
 * {@code JwtAuthenticationFilter}가 넣은 {@code Long userAccountId}다.
 *
 * <p>{@code QuizController}(조회)와 클래스를 나눈 이유: 조회는 정답을 숨기고 제출·이력은 정답을
 * 공개한다 — 반대 방향의 정보 규칙을 한 클래스에 섞지 않는다. {@code GET /submissions}는 리터럴
 * 경로라 {@code GET /{quizId}} 패턴보다 우선 매칭돼 충돌하지 않는다(Spring MVC 매핑 규칙).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /rt 은 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quizzes/**
@RequestMapping("/quizzes")
public class QuizSubmissionController {

    private final QuizSubmitService quizSubmitService;

    /**
     * 제출 즉시 채점. 없는·미편성 퀴즈 404, 없는 보기 번호 400, 재제출 409, 정답이면 포인트 적립.
     * {@code option} 누락 400은 {@code @Valid}가 컨트롤러 진입 전에 판정한다(404보다 우선).
     */
    @PostMapping("/{quizId}/submit")
    public ResponseEntity<ApiResponse<QuizSubmitResponse>> submit(@PathVariable Long quizId,
            @Valid @RequestBody QuizSubmitRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(
                quizSubmitService.submit(userAccountId, quizId, request.option())));
    }

    /** 내 풀이 이력(최신 제출부터, 서버 고정 20건 페이징) + 전체 정답률 요약. */
    @GetMapping("/submissions")
    public ResponseEntity<ApiResponse<QuizSubmissionHistoryResponse>> getSubmissions(
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(quizSubmitService.getHistory(userAccountId, page)));
    }
}
