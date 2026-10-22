package com.skhynix.quiz.quizroom.dto;

/** {@code result} 는 마지막 답일 때만 채워지고 그 전엔 {@code null} 키로 나간다. */
public record AnswerResponse(long answeredCount, int total, QuizRoomResultResponse result) {
}
