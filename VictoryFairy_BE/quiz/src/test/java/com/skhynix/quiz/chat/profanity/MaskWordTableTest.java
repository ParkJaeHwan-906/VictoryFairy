package com.skhynix.quiz.chat.profanity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구단별 치환어 표({@link MaskWordTable})가 요구사항 E절 표와 <b>순서까지</b> 일치하는지 고정한다
 * (QUIZ-CPF-29, 제약 9).
 *
 * <p>후보 선택이 해시 mod 후보 수라 목록의 <b>내용·순서·개수</b> 중 하나만 달라져도 같은 욕설이 다른
 * 단어로 치환된다. 그래서 여기서는 "한 구단에서 아무 단어나 나오면 통과"가 아니라, 프로브 문자열 200개를
 * 훑어 <b>모든 인덱스</b>가 기대 표와 같은 단어를 내놓는지 확인한다 — 순서가 뒤바뀌면 반드시 깨진다.
 */
class MaskWordTableTest {

    /** 요구사항 E절 표를 그대로 옮긴 것. 구현이 아니라 승인된 계약이 원본이다. */
    static Map<String, List<String>> expectedTable() {
        Map<String, List<String>> table = new LinkedHashMap<>();
        table.put("OB", List.of("두산", "망곰", "철웅이", "곰돌이"));
        table.put("LG", List.of("엘지", "럭키", "스타", "쌍둥이"));
        table.put("SS", List.of("삼성", "블레오", "사자"));
        table.put("KT", List.of("케이티", "위즈", "마법사"));
        table.put("WO", List.of("키움", "턱돌이", "히어로"));
        table.put("HT", List.of("기아", "호랑이", "호걸이"));
        table.put("HH", List.of("한화", "수리", "위니", "독수리"));
        table.put("NC", List.of("엔씨", "단디", "쎄리", "공룡"));
        table.put("LT", List.of("롯데", "누리", "아라", "거인"));
        table.put("SK", List.of("에스에스지", "랜디", "쓱"));
        return table;
    }

    static List<String> expectedFallback() {
        return List.of("야구", "직관", "응원");
    }

    private static List<String> probes() {
        return java.util.stream.IntStream.range(0, 200).mapToObj(i -> "probe-" + i).toList();
    }

    @Test
    @DisplayName("[QUIZ-CPF-29] 10개 구단 code 전부에 요구사항 표와 동일한 후보 목록이 순서까지 그대로 있다")
    void table_matchesRequirementTableIncludingOrder() {
        expectedTable().forEach((code, expected) -> {
            Set<String> seen = new LinkedHashSet<>();
            for (String probe : probes()) {
                String expectedWord = expected.get(Math.floorMod(probe.hashCode(), expected.size()));
                assertThat(MaskWordTable.pick(code, probe))
                        .as("구단 %s, 매칭 문자열 %s", code, probe)
                        .isEqualTo(expectedWord);
                seen.add(expectedWord);
            }
            // 프로브가 전 인덱스를 실제로 훑었는지 — 안 훑었다면 위 단언은 목록 일부만 본 셈이라 무의미하다.
            assertThat(seen).as("구단 %s 후보 커버리지", code).containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    @Test
    @DisplayName("[QUIZ-CPF-29] 구단 code 는 10개뿐이다 — 표에 없는 code 는 전부 폴백으로 떨어진다")
    void table_hasExactlyTenTeamCodes() {
        assertThat(expectedTable()).hasSize(10);
        for (String code : expectedTable().keySet()) {
            assertThat(MaskWordTable.pick(code, "시발")).isNotIn(expectedFallback());
        }
    }

    @Test
    @DisplayName("[QUIZ-CPF-37] 표에 없는 구단 code 는 공통 후보(야구·직관·응원)에서 같은 규칙으로 고른다")
    void pick_unknownCode_usesFallbackListWithSameRule() {
        List<String> fallback = expectedFallback();
        Set<String> seen = new LinkedHashSet<>();

        for (String probe : probes()) {
            String expectedWord = fallback.get(Math.floorMod(probe.hashCode(), fallback.size()));
            assertThat(MaskWordTable.pick("ZZ", probe)).isEqualTo(expectedWord);
            seen.add(expectedWord);
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(fallback);
    }

    @Test
    @DisplayName("[QUIZ-CPF-37] teams.code 가 null 이어도 NPE 없이 폴백 목록을 쓴다(Map.of 는 null 키 조회에 NPE 를 던진다)")
    void pick_nullCode_usesFallbackWithoutThrowing() {
        assertThat(MaskWordTable.pick(null, "시발")).isEqualTo("야구");
    }

    @Test
    @DisplayName("[QUIZ-CPF-28] 같은 (구단, 매칭 문자열) 이면 호출할 때마다 같은 단어다")
    void pick_isDeterministic() {
        assertThat(MaskWordTable.pick("OB", "시발"))
                .isEqualTo(MaskWordTable.pick("OB", "시발"))
                .isEqualTo("두산");
    }
}
