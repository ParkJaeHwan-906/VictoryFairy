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

    /**
     * <b>지목한 경기 한 건</b>의 이닝별 풀이 결산 — 이닝 배열 + 이닝별 요약 + 경기 전체 요약.
     *
     * <p><b>{@code gameId} 는 필수이고 페이징은 없다.</b> 값의 축은 {@code /today} 와 같은
     * {@code games.naver_game_id} 문자열이다(내부 PK 가 아니다 — {@code GameResponse.gameId} 로 이미
     * 노출된 그 값이라 FE 가 경기 식별자를 두 종류 들고 있지 않아도 된다). 조회 단위가 경기 하나라
     * 이닝 축을 통째로 그리는 화면이고, 그래서 페이지로 잘라 줄 수 없다.
     *
     * <p>미존재 경기는 404 {@code GAME_NOT_FOUND}, 아직 시작하지 않은 경기는 403
     * {@code GAME_NOT_STARTED} 다. <b>취소·종료 경기는 200</b> 이다 — 노게임이면 진행된 이닝의 기록이
     * 실제로 남아 있어 접으면 사용자가 자기 기록을 잃는다.
     */
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
