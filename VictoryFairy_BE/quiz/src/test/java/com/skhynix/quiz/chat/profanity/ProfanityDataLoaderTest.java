package com.skhynix.quiz.chat.profanity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 필터가 실제로 읽어 들이는 4종 JSON 의 내용을 고정한다.
 *
 * <p>{@link ProfanityFilterTest} 가 "이 문장이 치환되는가"를 보는 반면 여기서는 <b>데이터 자체</b>를 본다 —
 * 판정 결과가 우연히 같아도 목록에서 항목이 빠지면 {@code VictoryFairy_AI/validation} 쪽과 갈리기 때문이다
 * (QUIZ-CPF-32/42). AI 저장소는 이 저장소 밖이라 여기서 검증할 수 없고, BE 쪽 항목의 존재만 못박는다.
 */
class ProfanityDataLoaderTest {

    private final ProfanityData data = new ProfanityDataLoader(new ObjectMapper()).load();

    @Test
    @DisplayName("[QUIZ-CPF-31] general 카테고리에 새 변형어 샤갈·싸갈·야발이 들어 있다")
    void bannedWords_containNewVariants() {
        assertThat(data.bannedWords().get("general")).contains("샤갈", "싸갈", "야발");
    }

    @Test
    @DisplayName("[QUIZ-CPF-38/41] 공백 우회 검사 제외 목록은 코드가 아니라 데이터이고, 정확히 샤갈·싸갈·야발 셋뿐이다")
    void whitespaceStrictWords_areExactlyThreeAndComeFromData() {
        assertThat(data.whitespaceStrictWords()).containsExactlyInAnyOrder("샤갈", "싸갈", "야발");
    }

    @Test
    @DisplayName("[QUIZ-CPF-43] 예외 표현에 샤갈전·싸갈기 계열 7건이 모두 들어 있다"
            + "(어간 하나로는 \"싸갈겼다\"·\"싸갈길\" 이 덮이지 않는다 — 실측)")
    void exceptions_containAllSevenNewEntries() {
        assertThat(data.exceptions())
                .contains("샤갈전", "싸갈기", "싸갈겨", "싸갈겼", "싸갈길", "싸갈긴", "싸갈김");
    }

    @Test
    @DisplayName("[QUIZ-CPF-32] 금지어 카테고리 구성이 파이썬 원본과 같다(9개 카테고리, 각 카테고리 비어 있지 않음)")
    void bannedWords_haveSameCategoriesAsPythonSource() {
        assertThat(data.bannedWords().keySet()).containsExactlyInAnyOrder(
                "general", "sexual", "parent", "pyegeup", "disabled", "daegari", "mental", "gaessip", "aemchang");
        assertThat(data.bannedWords().values()).allSatisfy(words -> assertThat(words).isNotEmpty());
    }

    @Test
    @DisplayName("[QUIZ-CPF-10/제약 7] single_char 키는 전부 한 글자이고, 여러 글자 치환(77→ㄲ)은 multi_char 에 있다")
    void normalization_singleCharKeysAreOneCodePoint() {
        assertThat(data.singleCharNormalization().keySet())
                .allSatisfy(key -> assertThat(key.codePointCount(0, key.length())).isEqualTo(1));
        assertThat(data.multiCharNormalization()).containsEntry("77", "ㄲ");
    }
}
