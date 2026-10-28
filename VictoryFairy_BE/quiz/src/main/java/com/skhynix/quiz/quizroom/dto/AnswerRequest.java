package com.skhynix.quiz.quizroom.dto;

/**
 * {@code @NotNull} 을 두지 않는다 — 누락도 400 {@code QUIZ_OPTION_NOT_FOUND} 이고, 그 판정은 방·문항 404 뒤에 와야 한다
 * (검사 순서 404→404→400→409). 본문 검증으로 앞당기면 없는 방에 빈 본문을 보낸 요청이 404 대신 400 이 된다.
 */
public record AnswerRequest(Integer option) {
}
