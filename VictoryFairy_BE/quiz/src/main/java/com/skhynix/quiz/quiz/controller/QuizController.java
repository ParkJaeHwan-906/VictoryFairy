package com.skhynix.quiz.quiz.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.dto.QuizVoteRateResponse;
import com.skhynix.quiz.quiz.service.QuizLikeService;
import com.skhynix.quiz.quiz.service.QuizService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
// 접두사 /rt 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quizzes/**
// (MockMvc 는 context-path 를 적용하지 않으므로 슬라이스 테스트는 /quizzes/** 로 요청한다)
@RequestMapping("/quizzes")
public class QuizController {

    private final QuizService quizService;
    private final QuizLikeService quizLikeService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<QuizResponse>>> getTodayQuizzes(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam String gameId,
            @RequestParam(defaultValue = "false") boolean preferredOnly) {
        return ResponseEntity.ok(ApiResponse.ok(
                quizService.getTodayQuizzes(userAccountId, gameId, preferredOnly)));
    }

    @GetMapping("/{quizId}")
    public ResponseEntity<ApiResponse<QuizDetailResponse>> getQuiz(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long quizId) {
        return ResponseEntity.ok(ApiResponse.ok(quizService.getQuiz(userAccountId, quizId)));
    }

    @PostMapping("/{quizId}/like")
    public ResponseEntity<ApiResponse<QuizLikeResponse>> toggleLike(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long quizId) {
        return ResponseEntity.ok(ApiResponse.ok(quizLikeService.toggle(userAccountId, quizId)));
    }

    /**
     * 아직 답하지 않은 문제의 보기별 투표율. 자격이 없으면(받은 적 없음 · 이미 제출함) 상태 코드를
     * 가르지 않고 <b>200 + {@code data: null}</b> 로 답한다 — 404·403 으로 갈리면 응답 코드만으로
     * "그 사람이 그 문제를 받았는지"가 드러나고, 화면 쪽도 폴링 중에 에러 분기를 타게 된다.
     */
    @GetMapping("/{quizId}/vote-rate")
    public ResponseEntity<ApiResponse<QuizVoteRateResponse>> getQuizVoteRate(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long quizId) {
        return ResponseEntity.ok(ApiResponse.ok(quizService.getQuizVoteRate(userAccountId, quizId)));
    }
}
