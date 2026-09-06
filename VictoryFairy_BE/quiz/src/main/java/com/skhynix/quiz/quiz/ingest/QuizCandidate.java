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
        // v3 계약에서 필수가 됐지만 S3 에 이미 쌓인 v3 이전 파티션에는 없다(재처리 시 실제로 null 이
        // 온다) — 없을 때의 폴백은 DifficultyBqMapping 이고, 그 부재를 예외로 만들지 않는다.
        Integer bqReward,
        List<String> teamCodes,
        Subject subject) {

    public record Option(String id, String text) {
    }

    public record Subject(String scope, List<Long> playerIds, List<String> teamCodes,
            String gameId) {
    }
}
