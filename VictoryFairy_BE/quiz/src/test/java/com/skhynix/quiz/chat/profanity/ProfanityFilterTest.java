package com.skhynix.quiz.chat.profanity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ProfanityFilter} 단위 테스트 — 계약 원본은 {@code docs/requirements/quiz/chat-profanity-filter.md}.
 *
 * <p>실제 {@code quiz/src/main/resources/profanity/*.json} 을 그대로 읽어 돌린다(목 없음). 이 필터는
 * 상태·DB·외부 호출이 없는 순수 함수라 데이터까지 진짜로 물려야 계약이 검증된다.
 *
 * <p><b>치환어 기댓값을 문자열로 박아 둔 것은 의도다</b>(QUIZ-CPF-28/29, 제약 9). 후보 선택이
 * "매칭된 원문 문자열의 해시 mod 후보 수"라 목록의 순서·개수가 바뀌면 같은 욕설이 다른 단어로 치환된다.
 * 그때 이 테스트가 깨지는 것이 목적이다 — 목록 변경은 테스트 갱신을 동반하는 변경으로 취급한다.
 * 기댓값은 구현을 실행해 받아 적은 값이 아니라 {@code String.hashCode} 명세로 따로 계산한 값이다.
 */
class ProfanityFilterTest {

    /** 두산. 인수 기준의 기본 구단으로 쓴다. */
    private static final String OB = "OB";
    /** LG. "같은 욕설도 구단이 다르면 다른 단어"(QUIZ-CPF-28)를 보이는 대조군. */
    private static final String LG = "LG";

    private final ProfanityFilter filter =
            new ProfanityFilter(new ProfanityDataLoader(new ObjectMapper()));

    private String mask(String content) {
        return filter.mask(content, OB);
    }

    // ---------- A. 매칭이 없으면 원문 그대로 (QUIZ-CPF-8) ----------

    @Test
    @DisplayName("[QUIZ-CPF-8] 금지어가 없는 문장은 공백·이모지·구두점·대소문자까지 문자 단위로 원문과 동일하다")
    void mask_noProfanity_returnsIdenticalString() {
        String content = "오늘 경기 좋다!! 😀";

        assertThat(mask(content)).isEqualTo(content);
    }

    @ParameterizedTest(name = "[QUIZ-CPF-8] \"{0}\" 은 그대로 저장된다")
    @ValueSource(strings = {
            "9회말 역전 홈런 나왔다",
            "선발 투수 오늘 컨디션 좋네요",
            "심판 판정 좀 아쉽다 ㅠㅠ",
            "우리 팀 불펜 진짜 든든하다",
            "Let's go 오늘도 승리하자!",
    })
    @DisplayName("[QUIZ-CPF-8] 평범한 야구 채팅 문장은 치환되지 않는다(오탐 회귀 고정)")
    void mask_ordinaryBaseballChat_isNotMasked(String content) {
        assertThat(mask(content)).isEqualTo(content);
    }

    @Test
    @DisplayName("[QUIZ-CPF-8] null·빈 문자열은 그대로 돌려준다(방어 코드, 요구사항 미기재)")
    void mask_nullOrEmpty_returnsInput() {
        assertThat(filter.mask(null, OB)).isNull();
        assertThat(filter.mask("", OB)).isEmpty();
    }

    // ---------- B. 치환되는 표기들 (QUIZ-CPF-10~13, 27, 31) ----------

    @Test
    @DisplayName("[QUIZ-CPF-27] \"시발 오늘 왜 저럼\" 은 매칭 구간만 치환어 한 개로 바뀌고 나머지는 보존된다")
    void mask_profanityInSentence_replacesOnlyMatchedSpan() {
        assertThat(mask("시발 오늘 왜 저럼")).isEqualTo("두산 오늘 왜 저럼");
    }

    @Test
    @DisplayName("[QUIZ-CPF-15] 같은 자리에 여러 금지어가 걸리면 가장 긴 것을 쓴다 — \"개새끼\" 는 통째로 한 매칭이다"
            + "(\"새끼\" 만 잡혔다면 \"개\" 가 남아 \"개두산\" 이 됐을 것)")
    void mask_longestMatchWins_replacesWholeWord() {
        assertThat(mask("개새끼")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10/11] 초성 금지어 \"ㅅㅂ\" 는 호환 자모 접기 제외 덕에 살아남아 치환된다")
    void mask_choseongProfanity_isMasked() {
        assertThat(mask("ㅅㅂ")).isEqualTo("망곰");
    }

    @Test
    @DisplayName("[QUIZ-CPF-31] 새로 추가된 변형어 \"샤갈\"·\"싸갈\"·\"야발\" 자체는 치환된다")
    void mask_newlyAddedVariants_areMasked() {
        assertThat(mask("샤갈")).isEqualTo("두산");
        assertThat(mask("싸갈")).isEqualTo("두산");
        assertThat(mask("야발")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10/11] 특수문자를 끼워 넣은 회피 표기(\"씨@발\"·\"시!!발\")도 끼운 문자까지 함께 치환된다")
    void mask_specialCharEvasion_isMasked() {
        assertThat(mask("씨@발")).isEqualTo("두산");
        assertThat(mask("시!!발")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10/11] 숫자 치환(leet) 표기 \"s1b4l\" 은 정규화 후 영어 뷰에서 잡혀 치환된다")
    void mask_leetSpeak_isMasked() {
        assertThat(mask("s1b4l")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-12] 두벌식 자판 그대로 친 \"tlqkf\" 는 키보드 뷰에서 \"시발\" 로 복원돼 치환된다")
    void mask_keyboardLayout_isMasked() {
        assertThat(mask("tlqkf")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-13] 키보드 뷰는 완성형 음절 금지어만 본다 — \"oh\" 가 자판 복원되며 만드는 낱자 \"ㅗ\" 는 치환하지 않는다")
    void mask_keyboardViewIgnoresSingleJamoWords() {
        assertThat(mask("oh")).isEqualTo("oh");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10] 전각 표기 \"ｓｉｂａｌ\" 은 호환 문자 접기로 치환된다")
    void mask_fullWidth_isMasked() {
        assertThat(mask("ｓｉｂａｌ")).isEqualTo("곰돌이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10] 수학 볼드 표기(BMP 밖 서러게이트 쌍)도 접혀서 치환된다")
    void mask_mathematicalBold_isMasked() {
        assertThat(mask("𝘀𝗶𝗯𝗮𝗹")).isEqualTo("곰돌이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10/19] 프락투르 표기는 뒤쪽 5글자만 금지어라 그 구간만 치환되고 앞 한 글자는 원문 그대로 남는다")
    void mask_fraktur_masksOnlyMatchedSuffix() {
        String content = "𝖘𝖘𝖎𝖇𝖆𝖑";

        assertThat(mask(content)).isEqualTo("𝖘곰돌이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-10] 원문자 표기 \"ⓢⓘⓑⓐⓛ\" 도 접혀서 치환된다")
    void mask_circledLetters_isMasked() {
        assertThat(mask("ⓢⓘⓑⓐⓛ")).isEqualTo("철웅이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-16] 한 문장에 금지어가 둘이면 둘 다 치환한다(첫 매칭에서 멈추지 않는다)")
    void mask_multipleProfanities_masksAll() {
        assertThat(mask("시발 병신아")).isEqualTo("두산 곰돌이아");
    }

    // ---------- C. 원문 구간 역매핑 (QUIZ-CPF-19~22) ----------

    @Test
    @DisplayName("[QUIZ-CPF-19] \"시 발\" 은 되돌린 구간이 가운데 공백까지 삼켜 치환어 하나만 남는다(공백이 남지 않는다)")
    void mask_spaceInsideProfanity_swallowsTheSpace() {
        assertThat(mask("시 발")).isEqualTo("두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-40] 공백 엄격 목록에 없는 금지어의 공백 우회 탐지는 그대로 유지된다")
    void mask_whitespaceEvasionStillDetectedForOrdinaryWords() {
        assertThat(mask("시  발")).isEqualTo("두산");
        assertThat(mask("ㅅ ㅂ")).isEqualTo("곰돌이");
        assertThat(mask("ㅅ 발")).isEqualTo("망곰");
    }

    @Test
    @DisplayName("[QUIZ-CPF-20/22] 맞닿기만 한 두 구간은 병합하지 않는다 — \"시발시발\" 은 치환어 두 개가 된다. "
            + "동시에 여러 뷰가 같은 자리를 잡아도 치환어가 중복해서 쌓이지 않는다")
    void mask_adjacentMatches_produceTwoMaskWords() {
        assertThat(mask("시발시발")).isEqualTo("두산두산");
    }

    @Test
    @DisplayName("[QUIZ-CPF-21] 치환 구간 밖의 대소문자·공백·개행·이모지·구두점은 한 글자도 바뀌지 않는다")
    void mask_preservesEverythingOutsideMaskedSpan() {
        assertThat(mask("진짜 시발 ㅋㅋ")).isEqualTo("진짜 두산 ㅋㅋ");
        assertThat(mask("AaBb 시발!! 😀\n다음 줄")).isEqualTo("AaBb 두산!! 😀\n다음 줄");
    }

    @Test
    @DisplayName("[QUIZ-CPF-9] 치환으로 길이가 500자를 넘어도 필터는 자르거나 거절하지 않는다")
    void mask_resultMayExceed500Chars() {
        String content = "a".repeat(400) + "ㅗ".repeat(100); // 원문 500자(상한 통과)

        String masked = mask(content);

        assertThat(masked).hasSize(400 + 100 * "곰돌이".length());
        assertThat(masked.length()).isGreaterThan(500);
    }

    // ---------- D. 예외 표현 (QUIZ-CPF-23~25, 43) ----------

    @ParameterizedTest(name = "[QUIZ-CPF-24] \"{0}\" 은 예외 표현에 걸려 치환되지 않는다")
    @ValueSource(strings = {
            "보지도 못했다",
            "결정장애 온다",
            "수십년 만이다",
            "뒤진 경기",
    })
    @DisplayName("[QUIZ-CPF-23/24] 금지어와 겹치는 예외 표현이 있으면 그 매칭을 버린다(오탐 방지)")
    void mask_exceptionExpressions_areNotMasked(String content) {
        assertThat(mask(content)).isEqualTo(content);
    }

    @ParameterizedTest(name = "[QUIZ-CPF-43] \"{0}\" 은 부분 문자열 오탐이라 예외 표현으로 막는다")
    @ValueSource(strings = {
            "샤갈 전시회 갔다왔다",
            "싸갈기다",
            "싸갈겼다",
            "싸갈길",
    })
    @DisplayName("[QUIZ-CPF-43] 새 변형어(샤갈·싸갈)의 부분 문자열 오탐은 예외 표현 7건이 막는다"
            + "(공백 규칙으로는 안 걸러진다 — 글자가 실제로 붙어 있다)")
    void mask_newVariantSubstringFalsePositives_areNotMasked(String content) {
        assertThat(mask(content)).isEqualTo(content);
    }

    @ParameterizedTest(name = "[QUIZ-CPF-44] \"{0}\" 은 신체 부위라 치환되지 않는다")
    @ValueSource(strings = {
            "새끼손가락 다쳤대",
            "새끼발가락 밟혔다",
    })
    @DisplayName("[QUIZ-CPF-44] '새끼손'·'새끼발' 예외가 부상 얘기를 지켜 준다")
    void mask_bodyPartFalsePositives_areNotMasked(String content) {
        assertThat(mask(content)).isEqualTo(content);
    }

    @ParameterizedTest(name = "[QUIZ-CPF-44] \"{0}\" 은 여전히 치환된다")
    @ValueSource(strings = {
            "새끼",
            "개새끼",
            "이 새끼 저 새끼",
    })
    @DisplayName("[QUIZ-CPF-44] 신체 부위 예외가 '새끼' 자체까지 풀어 주지는 않는다")
    void mask_sakkiItself_isStillMasked(String content) {
        assertThat(mask(content)).isNotEqualTo(content);
    }

    // ---------- H. 공백 엄격 (QUIZ-CPF-39~41, 제약 8) ----------

    @ParameterizedTest(name = "[QUIZ-CPF-39] \"{0}\" 은 공백이 끼어 있어 치환되지 않는다")
    @ValueSource(strings = {
            "야 발표 준비하자",
            "이야 발이 빠르네",
            "대타 야 발 진짜",
            "야  발",
    })
    @DisplayName("[QUIZ-CPF-39] 공백 엄격 목록의 단어는 되돌린 원문 구간에 공백이 있으면 매칭을 버린다")
    void mask_whitespaceStrictWords_withSpace_areNotMasked(String content) {
        assertThat(mask(content)).isEqualTo(content);
    }

    @Test
    @DisplayName("[QUIZ-CPF-39/제약 8] 공백 판정은 유니코드 공백 전체다 — 전각 공백(U+3000)이 낀 \"야　발\" 도 치환되지 않는다")
    void mask_whitespaceStrictWords_withIdeographicSpace_areNotMasked() {
        String content = "야　발";

        assertThat(mask(content)).isEqualTo(content);
    }

    @Test
    @DisplayName("[알려진 사실 3] 공백 엄격 규칙은 공백만 본다 — 공백이 아닌 문자가 낀 \"야@발\"·\"야.발\" 은 여전히 치환된다"
            + "(정규화가 그 문자를 지워 두 글자가 붙기 때문이며, 되돌린 원문 문자열이 달라 치환어도 갈린다)")
    void mask_whitespaceStrictWords_withNonSpaceFiller_areStillMasked() {
        assertThat(mask("야@발")).isEqualTo("두산");
        assertThat(mask("야.발")).isEqualTo("철웅이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-20/39] 공백 엄격 단어도 붙어 있으면 매칭이며, 맞닿은 두 매칭은 치환어 두 개가 된다")
    void mask_whitespaceStrictWords_adjacentRepetition_producesTwoMaskWords() {
        assertThat(mask("야발야발")).isEqualTo("두산두산");
    }

    // ---------- E. 치환어 선택 (QUIZ-CPF-26~30, 37) ----------

    @Test
    @DisplayName("[QUIZ-CPF-28] 같은 구단·같은 입력이면 몇 번을 돌려도 같은 결과다(무작위·시각·계정 id가 개입하지 않는다)")
    void mask_isDeterministic() {
        String content = "시발 진짜 병신 같네";

        String first = mask(content);
        String second = mask(content);
        String third = filter.mask(content, OB);

        assertThat(first).isEqualTo(second).isEqualTo(third);
    }

    @Test
    @DisplayName("[QUIZ-CPF-26/28] 같은 욕설이라도 발신자의 응원 구단이 다르면 그 구단 목록 안의 다른 단어로 치환된다")
    void mask_sameProfanityDifferentTeam_usesThatTeamsWord() {
        assertThat(filter.mask("시발", OB)).isEqualTo("두산");
        assertThat(filter.mask("시발", LG)).isEqualTo("엘지");
    }

    @Test
    @DisplayName("[QUIZ-CPF-28] 한 메시지 안에서 같은 문자열의 두 매칭은 같은 단어로, 다른 문자열은 다른 단어가 될 수 있다")
    void mask_sameMatchedTextGetsSameWord() {
        assertThat(mask("시발시발")).isEqualTo("두산두산");
        assertThat(mask("시발 병신")).isEqualTo("두산 곰돌이");
    }

    @Test
    @DisplayName("[QUIZ-CPF-37] 치환어 표에 없는 구단 code 는 공통 후보(야구·직관·응원)로 폴백한다 — 욕설이 새지 않는다")
    void mask_unknownTeamCode_fallsBackToCommonWords() {
        assertThat(filter.mask("시발", "ZZ")).isEqualTo("야구");
        assertThat(filter.mask("ㅅㅂ", "ZZ")).isEqualTo("응원");
        assertThat(filter.mask("ㅅ ㅂ", "ZZ")).isEqualTo("직관");
    }

    @Test
    @DisplayName("[QUIZ-CPF-37] 응원 구단의 code 가 null 이어도(teams.code 는 nullable) 폴백으로 치환된다")
    void mask_nullTeamCode_fallsBackToCommonWords() {
        assertThat(filter.mask("시발", null)).isEqualTo("야구");
    }

    @Test
    @DisplayName("[QUIZ-CPF-30] 치환 결과를 다시 필터에 넣어도 추가 치환이 없다(치환어가 새 매칭을 만들지 않는다)")
    void mask_isIdempotent() {
        List<String> inputs = List.of(
                "시발 오늘 왜 저럼", "개새끼", "ㅅㅂ", "시발시발", "시발 병신아", "야발", "tlqkf", "s1b4l");

        for (String input : inputs) {
            String once = mask(input);
            assertThat(mask(once)).as("재입력: %s", once).isEqualTo(once);
        }
    }

    @Test
    @DisplayName("[QUIZ-CPF-30] 치환어 34개와 폴백 3개는 그 자체로 어떤 금지어·예외 패턴에도 걸리지 않는다")
    void maskWords_themselvesAreNeverMasked() {
        List<String> allMaskWords = MaskWordTableTest.expectedTable().values().stream()
                .flatMap(List::stream)
                .toList();

        for (String word : allMaskWords) {
            assertThat(mask(word)).as("치환어: %s", word).isEqualTo(word);
        }
        for (String word : MaskWordTableTest.expectedFallback()) {
            assertThat(mask(word)).as("폴백 치환어: %s", word).isEqualTo(word);
        }
    }
}
