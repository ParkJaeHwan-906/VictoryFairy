package com.skhynix.quiz.quiz.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * S3 후보 JSON → {@link QuizCandidate} 역직렬화 계약 테스트. 실제 후보 파일 형태의 문자열을
 * Jackson 3({@code tools.jackson.databind.ObjectMapper})로 파싱한다 — S3·Spring 컨텍스트 없음.
 *
 * <p>record 에 없는 계약 필드({@code evidence}·{@code settlement}·{@code status}·{@code createdAt}
 * 등)는 일부러 안 받는 설계라({@link QuizCandidate} javadoc), 모르는 필드가 있어도 파싱이 깨지지
 * 않는 것(Jackson 3 은 {@code FAIL_ON_UNKNOWN_PROPERTIES} 기본 off)이 곧 계약이다.
 */
class QuizCandidateDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("record에 없는 계약 필드(evidence·settlement·status·createdAt 등)가 섞인 실제 후보 "
            + "JSON도 모르는 필드를 무시하고 전 필드가 배선된 채 파싱된다")
    void deserialize_realCandidateJsonWithUnknownFields_ignoresThemAndWiresEveryField() {
        String json = """
                {
                  "quizId": "QZ-20260807-003",
                  "gameId": null,
                  "kind": "KNOWLEDGE",
                  "templateId": "CAREER_PATH",
                  "format": "MULTI4",
                  "question": "강백호가 FA로 새로 합류한 팀은?",
                  "options": [
                    {"id": "A", "text": "삼성"},
                    {"id": "B", "text": "두산"},
                    {"id": "C", "text": "한화"},
                    {"id": "D", "text": "NC"}
                  ],
                  "answer": "C",
                  "difficulty": "MEDIUM",
                  "pointReward": 30,
                  "teamCodes": [],
                  "subject": {"scope": "PLAYER", "playerIds": [50662], "teamCodes": []},
                  "evidence": {"source": "statiz", "url": "https://statiz.co.kr/player/50662"},
                  "settlement": null,
                  "status": "READY",
                  "createdAt": "2026-08-07T09:12:33+09:00",
                  "deadlineAt": null,
                  "createdBy": "quiz-routine"
                }
                """;

        QuizCandidate candidate = objectMapper.readValue(json, QuizCandidate.class);

        assertThat(candidate.quizId()).isEqualTo("QZ-20260807-003");
        assertThat(candidate.gameId()).isNull();
        assertThat(candidate.kind()).isEqualTo("KNOWLEDGE");
        assertThat(candidate.templateId()).isEqualTo("CAREER_PATH");
        assertThat(candidate.format()).isEqualTo("MULTI4");
        assertThat(candidate.question()).isEqualTo("강백호가 FA로 새로 합류한 팀은?");
        assertThat(candidate.options())
                .extracting(QuizCandidate.Option::id, QuizCandidate.Option::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", "삼성"),
                        org.assertj.core.groups.Tuple.tuple("B", "두산"),
                        org.assertj.core.groups.Tuple.tuple("C", "한화"),
                        org.assertj.core.groups.Tuple.tuple("D", "NC"));
        assertThat(candidate.answer()).isEqualTo("C");
        assertThat(candidate.difficulty()).isEqualTo("MEDIUM");
        assertThat(candidate.pointReward()).isEqualTo(30);
        assertThat(candidate.teamCodes()).isEmpty();
        // 정답 유출 방지 케이스: subject.teamCodes 가 null 이 아니라 "빈 배열"로 온다 — 로더가
        // 이 둘을 구분하므로 빈 배열이 빈 배열 그대로 배선되는 것이 중요하다
        assertThat(candidate.subject()).isNotNull();
        assertThat(candidate.subject().scope()).isEqualTo("PLAYER");
        assertThat(candidate.subject().playerIds()).containsExactly(50662L);
        assertThat(candidate.subject().teamCodes()).isNotNull().isEmpty();
        assertThat(candidate.subject().gameId()).isNull();
    }

    @Test
    @DisplayName("subject가 없는 구계약(v1) JSON도 파싱에 성공하고 subject는 null이다")
    void deserialize_v1JsonWithoutSubject_parsesWithNullSubject() {
        String json = """
                {
                  "quizId": "QZ-20260701-001",
                  "kind": "KNOWLEDGE",
                  "templateId": "MEME_ORIGIN",
                  "format": "OX",
                  "question": "'느그 자신 문동주'는 한화 팬덤에서 나온 말이다?",
                  "options": [
                    {"id": "A", "text": "O"},
                    {"id": "B", "text": "X"}
                  ],
                  "answer": "A",
                  "difficulty": "EASY",
                  "pointReward": 10,
                  "teamCodes": ["HH"],
                  "evidence": {"source": "community"},
                  "status": "READY",
                  "createdAt": "2026-07-01T09:00:00+09:00"
                }
                """;

        QuizCandidate candidate = objectMapper.readValue(json, QuizCandidate.class);

        assertThat(candidate.subject()).isNull();
        assertThat(candidate.quizId()).isEqualTo("QZ-20260701-001");
        assertThat(candidate.format()).isEqualTo("OX");
        assertThat(candidate.options()).hasSize(2);
        assertThat(candidate.teamCodes()).containsExactly("HH");
        assertThat(candidate.gameId()).isNull();
    }

    @Test
    @DisplayName("v2 GAME subject JSON은 subject.gameId가 배선되고 없는 필드(playerIds·teamCodes)는 null이다")
    void deserialize_v2GameSubject_wiresGameIdAndLeavesAbsentFieldsNull() {
        String json = """
                {
                  "quizId": "QZ-20260807-011",
                  "gameId": "20260804LGSK02026",
                  "kind": "KNOWLEDGE",
                  "templateId": "GAME_RESULT",
                  "format": "MULTI4",
                  "question": "8/4 LG-SSG전 승리투수는?",
                  "options": [
                    {"id": "A", "text": "임찬규"},
                    {"id": "B", "text": "김광현"},
                    {"id": "C", "text": "손주영"},
                    {"id": "D", "text": "송영진"}
                  ],
                  "answer": "A",
                  "difficulty": "HARD",
                  "pointReward": 50,
                  "subject": {"scope": "GAME", "gameId": "20260804LGSK02026"}
                }
                """;

        QuizCandidate candidate = objectMapper.readValue(json, QuizCandidate.class);

        assertThat(candidate.gameId()).isEqualTo("20260804LGSK02026");
        assertThat(candidate.subject()).isNotNull();
        assertThat(candidate.subject().scope()).isEqualTo("GAME");
        assertThat(candidate.subject().gameId()).isEqualTo("20260804LGSK02026");
        assertThat(candidate.subject().playerIds()).isNull();
        assertThat(candidate.subject().teamCodes()).isNull();
    }

    @Test
    @DisplayName("v2 MATCHUP subject JSON은 teamCodes 2개가 배열 순서 그대로 배선된다")
    void deserialize_v2MatchupSubject_wiresTwoTeamCodesInOrder() {
        String json = """
                {
                  "quizId": "QZ-20260807-007",
                  "kind": "KNOWLEDGE",
                  "templateId": "HEAD_TO_HEAD",
                  "format": "OX",
                  "question": "올해 한화는 KT 상대 우위다?",
                  "options": [
                    {"id": "A", "text": "O"},
                    {"id": "B", "text": "X"}
                  ],
                  "answer": "A",
                  "difficulty": "MEDIUM",
                  "pointReward": 30,
                  "subject": {"scope": "MATCHUP", "teamCodes": ["HH", "KT"]}
                }
                """;

        QuizCandidate candidate = objectMapper.readValue(json, QuizCandidate.class);

        assertThat(candidate.subject()).isNotNull();
        assertThat(candidate.subject().scope()).isEqualTo("MATCHUP");
        assertThat(candidate.subject().teamCodes()).containsExactly("HH", "KT");
        assertThat(candidate.subject().playerIds()).isNull();
        assertThat(candidate.subject().gameId()).isNull();
    }
}
