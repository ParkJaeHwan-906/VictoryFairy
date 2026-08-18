package com.skhynix.quiz.quiz.dto;

public record QuizSubmitResponse(
        boolean correct,
        int answer,
        int myOption,
        long earnedPoint,
        long totalPoint) {
}
