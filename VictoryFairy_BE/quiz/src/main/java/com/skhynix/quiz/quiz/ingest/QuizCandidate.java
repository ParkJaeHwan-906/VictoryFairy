package com.skhynix.quiz.quiz.ingest;

import java.util.List;

/**
 * S3 {@code quiz-candidates/{date}/{quizId}.json} 한 건의 역직렬화 형태 — AI 파이프라인 스펙 4.3
 * 계약(v1) + {@code subject}(v2, optional)만 담는다.
 *
 * <p><b>여기 없는 계약 필드({@code evidence}·{@code settlement}·{@code status}·{@code createdAt}·
 * {@code deadlineAt}·{@code createdBy})는 일부러 안 받는다</b> — RDB 에 저장하지 않는 필드라서다.
 * Jackson 3 는 기본값으로 모르는 필드를 무시하므로({@code FAIL_ON_UNKNOWN_PROPERTIES} 기본 off)
 * 계약에 필드가 늘어도 이 record 는 깨지지 않는다. 근거(evidence)가 필요해지면 {@code externalId}로
 * S3 원본을 다시 여는 것이 설계다(Quiz 엔티티 javadoc 참고).
 *
 * <p>{@code subject}는 "이 문제가 무엇에 관한 것인가"(주제)로, 귀속 축({@code gameId}·
 * {@code teamCodes})과 별개다. <b>AI 쪽 계약 개정 전에 올라간 후보에는 없다</b> — 그래서 nullable
 * 이고, 없으면 일반 문제로 적재된다(소급 분류는 불가능한 것이 맞다 — 정보가 전송된 적이 없다).
 */
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

    /** 보기 하나. {@code id}는 A부터 순서대로이며 배열 위치가 곧 RDB {@code option} 번호(0-기반)다. */
    public record Option(String id, String text) {
    }

    /**
     * 출제 주제(v2). {@code scope}: PLAYER | TEAM | MATCHUP | LEAGUE | GAME.
     * 정답 유출 방지 규칙에 따라 <b>문제가 전제하는 엔티티만</b> 담겨 온다 — 정답에만 등장하는
     * 엔티티는 생산자가 일부러 뺀다(예: CAREER_PATH 는 선수만 있고 팀은 빈다).
     */
    public record Subject(String scope, List<Long> playerIds, List<String> teamCodes,
            String gameId) {
    }
}
