package com.skhynix.quiz.quizroom.dto;

import com.skhynix.domain.quizroom.entity.QuizRoomQuizOption;

public record QuizRoomOptionResponse(int no, String text) {

    public static QuizRoomOptionResponse from(QuizRoomQuizOption option) {
        return new QuizRoomOptionResponse(option.getNo(), option.getText());
    }
}
