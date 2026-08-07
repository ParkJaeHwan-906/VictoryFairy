package com.skhynix.quiz.quiz.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
import com.skhynix.quiz.quiz.service.QuizService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

/**
 * 퀴즈 REST 엔드포인트. {@code /rt/quizzes/**}는 quiz {@code SecurityConfig}의
 * {@code anyRequest().authenticated()}에 걸려 자동으로 인증 필수다(채팅과 동일).
 */
@RestController
@RequiredArgsConstructor
// 접두사 /rt 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quizzes/**
// (MockMvc 는 context-path 를 적용하지 않으므로 슬라이스 테스트는 /quizzes/** 로 요청한다)
@RequestMapping("/quizzes")
public class QuizController {

    private final QuizService quizService;

    /**
     * 오늘(KST) 출제분 목록. 정답은 응답에 없다({@link QuizResponse} 참고 — 채점은 제출 API 몫).
     * 출제분이 없는 날(루틴 미실행·실패)은 200 + 빈 배열이다 — 클라이언트가 "오늘은 퀴즈가 없어요"
     * 상태를 구분할 필요가 없게 에러로 만들지 않는다.
     */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<QuizResponse>>> getTodayQuizzes() {
        return ResponseEntity.ok(ApiResponse.ok(quizService.getTodayQuizzes()));
    }
}
