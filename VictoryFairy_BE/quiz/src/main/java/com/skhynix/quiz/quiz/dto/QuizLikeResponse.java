package com.skhynix.quiz.quiz.dto;

public record QuizLikeResponse(boolean liked, long likeCount) {

    public static QuizLikeResponse none() {
        return new QuizLikeResponse(false, 0L);
    }
}
