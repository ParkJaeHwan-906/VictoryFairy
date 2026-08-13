package com.skhynix.quiz.quiz.dto;

import java.util.List;

public record QuizSubmissionHistoryResponse(Summary summary, List<InningResponse> innings) {

    public record Summary(long correctCount, long total, double accuracy, long earnedPoint) {
    }

    public record InningSummary(long correctCount, long total, double accuracy) {
    }

    public record InningResponse(int inning, InningSummary summary,
            List<QuizSubmissionItemResponse> quizzes) {

        public static InningResponse of(int inning, List<QuizSubmissionItemResponse> quizzes) {
            long correctCount = quizzes.stream().filter(QuizSubmissionItemResponse::correct).count();
            return new InningResponse(inning,
                    new InningSummary(correctCount, quizzes.size(),
                            accuracy(correctCount, quizzes.size())),
                    quizzes);
        }
    }

    public static QuizSubmissionHistoryResponse of(List<InningResponse> innings) {
        long total = innings.stream().mapToLong(inning -> inning.summary().total()).sum();
        long correctCount = innings.stream()
                .mapToLong(inning -> inning.summary().correctCount()).sum();
        long earnedPoint = innings.stream()
                .flatMap(inning -> inning.quizzes().stream())
                .mapToLong(QuizSubmissionItemResponse::earnedPoint)
                .sum();
        return new QuizSubmissionHistoryResponse(
                new Summary(correctCount, total, accuracy(correctCount, total), earnedPoint),
                innings);
    }

    private static double accuracy(long correctCount, long total) {
        return total == 0 ? 0.0 : (double) correctCount / total;
    }
}
