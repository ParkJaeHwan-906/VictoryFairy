package com.skhynix.quiz.quizroom.dto;

import java.util.List;

public record QuizRoomResultResponse(int correctCount, int total, List<ResultQuizResponse> quizzes) {
}
