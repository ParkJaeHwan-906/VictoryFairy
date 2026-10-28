package com.skhynix.quiz.quizroom.dto;

import java.util.List;

public record ResultQuizResponse(
        Long quizId,
        String type,
        String question,
        List<QuizRoomOptionResponse> options,
        int myOption,
        int answer,
        boolean correct) {
}
