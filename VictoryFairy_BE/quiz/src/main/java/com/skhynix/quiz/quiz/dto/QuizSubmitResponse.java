package com.skhynix.quiz.quiz.dto;

/**
 * 제출 즉시 채점 결과. 제출이 성립한 뒤에만 내려가므로 {@code answer}(정답 번호)를 실어도 유출이
 * 아니다 — 재제출은 UNIQUE 가 막아 이 응답으로 정답을 알아도 다시 쓸 곳이 없다
 * ({@code QuizResponse}가 정답을 숨기는 것과 짝을 이루는 규칙).
 *
 * @param earnedPoint 이번 제출로 적립된 포인트(오답·배점 없음이면 0)
 * @param totalPoint  적립 반영 후 보유 포인트 총액 — FE 가 잔액 재조회 없이 갱신하도록 싣는다
 */
public record QuizSubmitResponse(
        boolean correct,
        int answer,
        int myOption,
        long earnedPoint,
        long totalPoint) {
}
