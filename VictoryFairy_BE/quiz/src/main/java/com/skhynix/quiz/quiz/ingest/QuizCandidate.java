package com.skhynix.quiz.quiz.ingest;

import java.util.List;

public record QuizCandidate(
        String quizId,
        String gameId,
        String kind,
        String templateId,
        String format,
        String question,
        List<Option> options,
        String answer,
        String difficulty,
        Integer pointReward,
        List<String> teamCodes,
        Subject subject) {

    public record Option(String id, String text) {
    }

    public record Subject(String scope, List<Long> playerIds, List<String> teamCodes,
            String gameId) {
    }
}
