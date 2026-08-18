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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
// 접두사 /rt 은 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quizzes/**
@RequestMapping("/quizzes")
public class QuizSubmissionController {

    private final QuizSubmitService quizSubmitService;

    @PostMapping("/{quizId}/submit")
    public ResponseEntity<ApiResponse<QuizSubmitResponse>> submit(@PathVariable Long quizId,
            @Valid @RequestBody QuizSubmitRequest request,
            @AuthenticationPrincipal Long userAccountId) {
        return ResponseEntity.ok(ApiResponse.ok(
                quizSubmitService.submit(userAccountId, quizId, request.option())));
    }

    @GetMapping("/submissions")
    public ResponseEntity<ApiResponse<QuizSubmissionHistoryResponse>> getSubmissions(
            @RequestParam String gameId,
            @AuthenticationPrincipal Long userAccountId)
            throws MissingServletRequestParameterException {
        // 빈 문자열은 누락과 같은 400 으로 접는다. 그대로 흘려보내면 "존재하지 않는 경기"(404)로
        // 잘못 안내되고, 값을 정하지도 않은 요청에 조회가 한 번 나간다.
        // ⚠ 새 ErrorCode 를 만들지 않는 것은 의도다 — 이 400 의 계약은 이미 스프링의 필수 파라미터
        //   누락 응답(GlobalExceptionHandler)이 정의하고 있고, 사용자가 볼 사건은 둘이 같다.
        if (gameId.isBlank()) {
            throw new MissingServletRequestParameterException("gameId", "String");
        }
        return ResponseEntity.ok(
                ApiResponse.ok(quizSubmitService.getHistory(userAccountId, gameId)));
    }
}
