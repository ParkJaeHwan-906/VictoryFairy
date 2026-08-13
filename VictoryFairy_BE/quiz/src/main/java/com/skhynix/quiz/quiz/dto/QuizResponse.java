package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import java.util.List;

public record QuizResponse(
        Long id,
        String type,
        String question,
        String difficulty,
        Double point,
        boolean preferred,
        List<OptionResponse> options) {

    public record OptionResponse(int no, String text) {

        static OptionResponse from(QuizOption option) {
            return new OptionResponse(option.getOption(), option.getContents());
        }
    }

    public static QuizResponse of(Quiz quiz, List<QuizOption> options, boolean preferred) {
        return new QuizResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getScore(),
                preferred,
                options.stream().map(OptionResponse::from).toList());
    }
}
