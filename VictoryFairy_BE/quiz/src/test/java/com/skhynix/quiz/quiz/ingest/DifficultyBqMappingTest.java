package com.skhynix.quiz.quiz.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link DifficultyBqMapping} 단위 테스트. 계약: {@code docs/requirements/quiz/quiz-point-bq-split.md}
 * QUIZ-PBQ-6~8. 이 앱의 유일한 정의이고, 마이그레이션 백필(SQL CASE)은 같은 규칙의 두 번째 사본이라
 * SQL 실행 없이는(H2/Testcontainers/구동 중인 MySQL 부재) 여기서 함께 검증할 수 없다(QUIZ-PBQ-14는
 * 이 테스트만으로 완결되지 않음 — 최종 보고에서 별도 표기).
 */
class DifficultyBqMappingTest {

    private static Stream<Arguments> knownDifficulties() {
        return Stream.of(
                Arguments.of("EASY", 1),
                Arguments.of("MEDIUM", 2),
                Arguments.of("HARD", 3),
                Arguments.of("EXPERT", 4));
    }

    @ParameterizedTest(name = "[QUIZ-PBQ-6] {0} -> {1}")
    @MethodSource("knownDifficulties")
    @DisplayName("[QUIZ-PBQ-6] 네 난이도 각각이 정확한 bq 값으로 매핑된다")
    void bqOf_knownDifficulty_returnsMappedValue(String difficulty, Integer expectedBq) {
        assertThat(DifficultyBqMapping.bqOf(difficulty)).isEqualTo(expectedBq);
    }

    @Test
    @DisplayName("[QUIZ-PBQ-7] 치역은 정확히 {1,2,3,4}다 — 어떤 난이도 입력으로도 5가 나오지 않는다"
            + "(5는 예약값. 상위 난이도가 신설되면 이 단언이 의도적으로 깨져야 하는 자리다)")
    void bqOf_range_isExactlyOneToFourNeverFive() {
        java.util.List<Integer> range = knownDifficulties()
                .map(args -> (Integer) args.get()[1])
                .toList();

        assertThat(range).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(range).doesNotContain(5);
        // 표에 없는(미래 확장 포함) 난이도는 5를 만들어 내지 않는다 — null이다(QUIZ-PBQ-8과 함께 고정)
        assertThat(DifficultyBqMapping.bqOf("LEGEND")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEGEND", "", "easy", "Medium"})
    @DisplayName("[QUIZ-PBQ-8] 매핑표에 없는 난이도 문자열은 예외 없이 null을 반환한다(대소문자 변형·"
            + "빈 문자열·미래 난이도 포함)")
    void bqOf_unmappedDifficulty_returnsNullWithoutException(String difficulty) {
        assertThat(DifficultyBqMapping.bqOf(difficulty)).isNull();
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("[QUIZ-PBQ-8] 난이도가 NULL이면 예외 없이 null을 반환한다")
    void bqOf_nullDifficulty_returnsNullWithoutException(String difficulty) {
        assertThat(DifficultyBqMapping.bqOf(difficulty)).isNull();
    }
}
