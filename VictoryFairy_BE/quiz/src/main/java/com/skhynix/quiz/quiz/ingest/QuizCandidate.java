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
        Subject subject,
        // PREDICTION 전용 정산 지시. KNOWLEDGE 후보는 null(QuizCandidateDeserializationTest 가
        // settlement:null 로 그 경로를 고정). kind=="PREDICTION" && settlement.metric()이 지원되는
        // 지표(지금은 BATTER_HIT_IN_INNING 하나)일 때만 QuizIngestService 가 적재한다 — 그 외
        // PREDICTION(settlement null 포함)은 여전히 SKIPPED_PREDICTION.
        Settlement settlement) {

    public record Option(String id, String text) {
    }

    public record Subject(String scope, List<Long> playerIds, List<String> teamCodes,
            String gameId) {
    }

    /**
     * PREDICTION 후보의 정산 좌표. {@code gameId}는 naverGameId 축(최상단 {@code gameId}·
     * {@code subject.gameId}와 같은 값 체계)이며, 정산이 어느 경기를 볼지 분기 없이 바로 가리키도록
     * 여기 다시 담는다. {@code half}는 {@link com.skhynix.domain.game.entity.InningHalf#name()}
     * 문자열("TOP"/"BOTTOM")이다.
     */
    public record Settlement(String metric, String gameId, Integer inning, String half) {
    }
}
