package com.skhynix.quiz.quiz.dto;

import com.skhynix.quiz.chat.dto.PageResponse;

/**
 * 풀이 이력 응답 = 요약 + 페이지. 요약(전체 정답률)이 페이지 메타가 아니라 <b>전체 제출 기준</b>
 * 별도 카운트인 것이 계약이다 — 어느 페이지를 보고 있어도 같은 값이 내려간다.
 *
 * <p>{@code PageResponse}는 chat 패키지의 것을 그대로 쓴다(cross-package 재사용) — 페이징 응답
 * 포맷은 모듈 공통 규약이라 복제하면 두 포맷이 서로 어긋나는 순간이 온다.
 *
 * @param summary accuracy 는 {@code correctCount/total}(0~1). 제출 0건이면 0.0 — NaN 을 JSON 에
 *                실을 수 없어 "정답률 없음"을 0 으로 접는다
 */
public record QuizSubmissionHistoryResponse(
        Summary summary,
        PageResponse<QuizSubmissionItemResponse> submissions) {

    public record Summary(long total, long correctCount, double accuracy) {
    }
}
