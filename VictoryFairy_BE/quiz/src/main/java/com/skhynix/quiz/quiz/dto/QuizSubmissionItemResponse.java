package com.skhynix.quiz.quiz.dto;

import com.skhynix.domain.quiz.entity.Quiz;
import com.skhynix.domain.quiz.entity.QuizOption;
import com.skhynix.domain.quiz.entity.QuizUserSubmit;
import java.time.LocalDateTime;
import java.util.List;

public record QuizSubmissionItemResponse(
        Long quizId,
        String question,
        String type,
        String difficulty,
        List<OptionResponse> options,
        Integer myOption,
        boolean correct,
        boolean expired,
        int answer,
        long earnedPoint,
        LocalDateTime submittedAt,
        boolean liked,
        long likeCount) {

    public static QuizSubmissionItemResponse from(QuizUserSubmit submit, List<QuizOption> options,
            QuizLikeResponse like, boolean expired) {
        Quiz quiz = submit.getQuiz();
        // 답이 없으면 isAnswer 는 "아직 채점 안 됨"의 false 라 적립도 0 이다 — 오답과 같은 표시가 된다.
        // score 는 nullable(사람이 쓴 퀴즈)이라 없으면 0 으로 센다(예외로 만들지 않는다).
        long earnedPoint = submit.isAnswer() && quiz.getScore() != null
                ? Math.round(quiz.getScore())
                : 0L;
        QuizOption myOption = submit.getSubmitOption();
        return new QuizSubmissionItemResponse(
                quiz.getId(),
                quiz.getContent(),
                quiz.getQuizType().getName(),
                quiz.getDifficulty(),
                options.stream().map(OptionResponse::from).toList(),
                myOption == null ? null : myOption.getOption(),
                submit.isAnswer(),
                expired,
                quiz.getAnswer(),
                earnedPoint,
                submit.getUpdatedAt(),
                like.liked(),
                like.likeCount());
    }
}
