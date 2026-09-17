package com.skhynix.quiz.quiz.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuizDetailResponse(
        Long id,
        String type,
        String question,
        String difficulty,
        Double point,
        Integer bq,
        LocalDate quizDate,
        List<OptionResponse> options,
        boolean submitted,
        boolean expired,
        Integer myOption,
        Boolean correct,
        Integer answer,
        Boolean liked,
        Long likeCount) {

    public static QuizDetailResponse unsubmitted(Quiz quiz, List<QuizOption> options,
            boolean expired) {
        return of(quiz, options, false, expired, null, null, null, null, null);
    }

    public static QuizDetailResponse submitted(Quiz quiz, List<QuizOption> options,
            QuizUserSubmit submit, QuizLikeResponse like) {
        return of(quiz, options, true, false,
                submit.getSubmitOption().getOption(), submit.isAnswer(), quiz.getAnswer(),
                like.liked(), like.likeCount());
    }

    private static QuizDetailResponse of(Quiz quiz, List<QuizOption> options, boolean submitted,
            boolean expired, Integer myOption, Boolean correct, Integer answer, Boolean liked,
            Long likeCount) {
        return new QuizDetailResponse(
                quiz.getId(),
                quiz.getQuizType().getName(),
                quiz.getContent(),
                quiz.getDifficulty(),
                quiz.getPoint(),
                // NON_NULL 이라 null 이면 키 자체가 빠진다 — point 와 같은 규칙이다
                quiz.getBq(),
                quiz.getQuizDate(),
                options.stream().map(OptionResponse::from).toList(),
                submitted,
                expired,
                myOption,
                correct,
                answer,
                liked,
                likeCount);
    }
}
