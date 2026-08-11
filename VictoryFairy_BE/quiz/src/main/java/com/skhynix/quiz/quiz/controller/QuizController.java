package com.skhynix.quiz.quiz.controller;

import com.skhynix.common.response.ApiResponse;
import com.skhynix.quiz.quiz.dto.QuizDetailResponse;
import com.skhynix.quiz.quiz.dto.QuizLikeResponse;
import com.skhynix.quiz.quiz.dto.QuizResponse;
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

/**
 * 퀴즈 REST 엔드포인트. {@code /rt/quizzes/**}는 quiz {@code SecurityConfig}의
 * {@code anyRequest().authenticated()}에 걸려 자동으로 인증 필수다(채팅과 동일).
 * principal 은 {@code JwtAuthenticationFilter}가 넣는 {@code users_account.id}(Long)다.
 */
@RestController
@RequiredArgsConstructor
// 접두사 /rt 는 server.servlet.context-path 가 붙인다 → 실제 노출 경로는 /rt/quizzes/**
// (MockMvc 는 context-path 를 적용하지 않으므로 슬라이스 테스트는 /quizzes/** 로 요청한다)
@RequestMapping("/quizzes")
public class QuizController {

    private final QuizService quizService;
    private final QuizLikeService quizLikeService;

    /**
     * 오늘(KST) 출제분 목록 — 선호(응원팀·응원 선수 관련) 먼저 정렬. {@code preferredOnly=true}면
     * 선호 문제만(응원 정보가 없으면 전체 — 서비스 javadoc). 정답은 응답에 없다
     * ({@link QuizResponse} 참고 — 채점은 제출 API 몫). 출제분이 없는 날(루틴 미실행·실패)은
     * 200 + 빈 배열이다 — 클라이언트가 "오늘은 퀴즈가 없어요" 상태를 구분할 필요가 없게 에러로
     * 만들지 않는다.
     */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<QuizResponse>>> getTodayQuizzes(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam(defaultValue = "false") boolean preferredOnly) {
        return ResponseEntity.ok(ApiResponse.ok(
                quizService.getTodayQuizzes(userAccountId, preferredOnly)));
    }

    /**
     * 단건 상세. 미존재·미편성(풀 대기) 모두 404 다 — 편성 전 문제의 존재를 숨긴다(서비스 javadoc).
     * 내 제출이 있으면 선택·정오·정답이 실리고, 없으면 그 키들 자체가 응답에 없다
     * ({@link QuizDetailResponse}).
     */
    @GetMapping("/{quizId}")
    public ResponseEntity<ApiResponse<QuizDetailResponse>> getQuiz(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long quizId) {
        return ResponseEntity.ok(ApiResponse.ok(quizService.getQuiz(userAccountId, quizId)));
    }

    /**
     * 좋아요 토글(요청 본문 없음 — 다음 상태는 서버가 정한다). 응답의 {@code liked}가 화면 상태의
     * 정본이다: 멱등이 아니라 같은 요청을 두 번 보내면 원상 복귀한다.
     *
     * <p><b>내가 제출한 문제에만 허용된다.</b> 미존재·미편성·미제출은 전부 같은 403 이며 클라이언트는
     * 셋을 구분할 수 없다(서비스 javadoc — 구분해 주면 편성 전 문제의 존재가 새어 나간다).
     */
    @PostMapping("/{quizId}/like")
    public ResponseEntity<ApiResponse<QuizLikeResponse>> toggleLike(
            @AuthenticationPrincipal Long userAccountId,
            @PathVariable Long quizId) {
        return ResponseEntity.ok(ApiResponse.ok(quizLikeService.toggle(userAccountId, quizId)));
    }
}
