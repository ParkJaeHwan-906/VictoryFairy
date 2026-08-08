package com.skhynix.quiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 퀴즈 제출 요청. {@code option}은 {@code QuizResponse.OptionResponse.no}로 받은 보기 번호
 * (0-기반, O/X 는 0=O·1=X)를 그대로 되돌려 보내는 값이다.
 *
 * <p>{@code @NotNull}만 걸고 범위 검증을 두지 않는 이유: 보기 개수가 문제마다 달라 정적 범위가
 * 없다 — 실재하지 않는 번호는 서비스가 보기 조회 실패(400 {@code QUIZ_OPTION_NOT_FOUND})로 판정한다.
 * {@code @Valid}는 컨트롤러 진입 전에 검증되므로 누락 400이 퀴즈 미존재 404보다 먼저 판정된다.
 */
public record QuizSubmitRequest(

        @NotNull
        Integer option
) {
}
