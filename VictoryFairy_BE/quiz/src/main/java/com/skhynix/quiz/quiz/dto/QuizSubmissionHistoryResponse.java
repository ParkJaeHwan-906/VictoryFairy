package com.skhynix.quiz.quiz.dto;

import java.util.List;

public record QuizSubmissionHistoryResponse(Summary summary, List<InningResponse> innings) {

    public record Summary(long correctCount, long total, double accuracy, long earnedPoint,
            long earnedBq) {
    }

    /**
     * 이닝별 요약. <b>배점 합계를 담지 않는다</b>(경기 전체 {@link Summary} 에만 있다) — 두 축이 같은
     * 자리에 있어야 나중에 한쪽만 옮기는 실수가 안 생긴다. 이닝별 합계가 필요하면 FE 가 그 이닝의
     * 문제 항목 {@code earnedPoint}/{@code earnedBq} 를 더하면 된다.
     */
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
        long earnedBq = innings.stream()
                .flatMap(inning -> inning.quizzes().stream())
                .mapToLong(QuizSubmissionItemResponse::earnedBq)
                .sum();
        return new QuizSubmissionHistoryResponse(
                new Summary(correctCount, total, accuracy(correctCount, total), earnedPoint,
                        earnedBq),
                innings);
    }

    private static double accuracy(long correctCount, long total) {
        return total == 0 ? 0.0 : (double) correctCount / total;
    }
}
