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
     * ({@link QuizResponse} 참고 — 채점은 제출 API 몫).
     *
     * <p><b>{@code gameId} 는 필수다</b> — 값은 내부 PK 가 아니라 {@code games.naver_game_id}
     * 문자열(예: {@code 20260812LGWO02026})이고, {@code GameResponse.gameId} 로 이미 노출된 그 값이다.
     * 클라이언트가 <b>지금 보고 있는 자기 팀 경기를 지목</b>하면 서버가 그 경기를 검증하고
     * (오늘·내 응원 구단·진행 중) 그 경기의 현재 이닝을 기록에 남긴다. 이닝은 파라미터로 받지
     * 않는다 — 받게 하면 아무 숫자나 보내 "이닝당 1회"를 무한히 우회할 수 있다.
     *
     * <p>⚠ <b>{@code required = false} + 기본값으로 바꾸지 말 것.</b> 누락을 흡수하면 서버가 경기를
     * 알아서 고르던 예전 동작으로 조용히 되돌아가고, 그 순간 회차 제한의 키가 무너진다.
     *
     * <p>성공은 200 이고, 세트를 <b>줄 수 있는데 줄 게 없으면</b>(오늘 세트 없음·남은 문제를 이미
     * 다 받음) 빈 배열이다. "지금은 줄 수 없다"는 전부 에러다 — 그 이닝에 이미 받았으면 409
     * {@code QUIZ_ALREADY_SERVED_IN_INNING}, 그 밖의 제공 불가는 403 {@code QUIZ_NOT_SERVABLE}.
     */
    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<QuizResponse>>> getTodayQuizzes(
            @AuthenticationPrincipal Long userAccountId,
            @RequestParam String gameId,
            @RequestParam(defaultValue = "false") boolean preferredOnly) {
        return ResponseEntity.ok(ApiResponse.ok(
                quizService.getTodayQuizzes(userAccountId, gameId, preferredOnly)));
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
