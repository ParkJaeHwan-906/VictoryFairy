package com.skhynix.profanity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 탐지 장치의 공개 API({@link ProfanityDetector#detect}, {@link ProfanityDetector#maskWithAsterisks}) 계약.
 * 구간 좌표는 {@link String#substring(int, int)} 규약(UTF-16 code unit, end 제외)이다.
 */
class ProfanityDetectorTest {

    private final ProfanityDetector detector = new ProfanityDetector(new ProfanityDataLoader(new ObjectMapper()));

    @Test
    @DisplayName("[CHAT-GC-56] 금지어가 한 번 나오면 그 원문 구간 하나를 start 포함·end 제외 좌표로 돌려준다")
    void detect_singleMatch_returnsOriginSpan() {
        List<ProfanitySpan> spans = detector.detect("시발 오늘");

        assertThat(spans).containsExactly(new ProfanitySpan(0, 2));
        assertThat("시발 오늘".substring(spans.get(0).start(), spans.get(0).end())).isEqualTo("시발");
    }

    @Test
    @DisplayName("[CHAT-GC-56] 금지어가 문장 중간에 있으면 앞 문자 수만큼 밀린 좌표를 돌려준다")
    void detect_matchInMiddle_returnsShiftedSpan() {
        String text = "오늘 정말 개새끼 같다";

        List<ProfanitySpan> spans = detector.detect(text);

        assertThat(spans).hasSize(1);
        assertThat(text.substring(spans.get(0).start(), spans.get(0).end())).isEqualTo("개새끼");
        assertThat(spans.get(0).start()).isEqualTo(6);
    }

    @Test
    @DisplayName("[CHAT-GC-54] 글자 사이 공백이 낀 우회 표기는 공백을 포함한 원문 구간 전체(길이 3)로 돌려준다")
    void detect_whitespaceBypass_spanIncludesWhitespace() {
        List<ProfanitySpan> spans = detector.detect("시 발");

        assertThat(spans).containsExactly(new ProfanitySpan(0, 3));
    }

    @Test
    @DisplayName("[CHAT-GC-56] 맞닿기만 한 두 매칭(시발시발)은 합치지 않고 구간 두 개로 돌려준다")
    void detect_adjacentMatches_areNotMerged() {
        List<ProfanitySpan> spans = detector.detect("시발시발");

        assertThat(spans).containsExactly(new ProfanitySpan(0, 2), new ProfanitySpan(2, 4));
    }

    @Test
    @DisplayName("[CHAT-GC-56] 시작 위치 오름차순으로 돌려준다")
    void detect_multipleMatches_areSortedByStart() {
        List<ProfanitySpan> spans = detector.detect("개새끼 그리고 시발");

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).start()).isLessThan(spans.get(1).start());
        assertThat(spans.get(0).end()).isLessThanOrEqualTo(spans.get(1).start());
    }

    @Test
    @DisplayName("[CHAT-GC-56] 금지어가 아닌 문장(발표·새끼손가락)은 탐지하지 않는다")
    void detect_innocentWords_returnsEmpty() {
        assertThat(detector.detect("야 발표 준비하자")).isEmpty();
        assertThat(detector.detect("새끼손가락")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-56] null 과 빈 문자열은 빈 목록이다")
    void detect_nullAndEmpty_returnsEmpty() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect("")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-56] 반환 목록은 수정할 수 없다")
    void detect_result_isUnmodifiable() {
        List<ProfanitySpan> spans = detector.detect("시발");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> spans.add(new ProfanitySpan(0, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-54] '시발 오늘' 은 '** 오늘' 로 마스킹되고 길이가 원문과 같다")
    void maskWithAsterisks_replacesSpanWithSameLengthAsterisks() {
        String masked = detector.maskWithAsterisks("시발 오늘");

        assertThat(masked).isEqualTo("** 오늘");
        assertThat(masked).hasSameSizeAs("시발 오늘");
    }

    @Test
    @DisplayName("[CHAT-GC-54] '시 발' 은 공백을 포함해 '***' 로, '개새끼' 는 '***' 로 마스킹된다")
    void maskWithAsterisks_whitespaceBypassAndPlainWord() {
        assertThat(detector.maskWithAsterisks("시 발")).isEqualTo("***");
        assertThat(detector.maskWithAsterisks("개새끼")).isEqualTo("***");
    }

    @Test
    @DisplayName("[CHAT-GC-54] 맞닿은 금지어 둘은 각각 마스킹되어 전체가 별표가 된다")
    void maskWithAsterisks_adjacentMatches() {
        assertThat(detector.maskWithAsterisks("시발시발")).isEqualTo("****");
    }

    @Test
    @DisplayName("[CHAT-GC-54] 매칭이 없으면 원문과 동일하고, null·빈 문자열은 그대로 돌려준다")
    void maskWithAsterisks_noMatch_returnsOriginal() {
        assertThat(detector.maskWithAsterisks("야 발표 준비하자")).isEqualTo("야 발표 준비하자");
        assertThat(detector.maskWithAsterisks(null)).isNull();
        assertThat(detector.maskWithAsterisks("")).isEmpty();
    }

    @Test
    @DisplayName("[CHAT-GC-54] 마스킹 결과 길이는 어떤 입력에서도 원문 길이와 같다")
    void maskWithAsterisks_lengthAlwaysEqualsOriginal() {
        for (String text : List.of("시발 오늘", "시 발", "개새끼들아 시발시발 ㅅㅂ", "이모지😀 시발 😀", "  개 새 끼  ")) {
            assertThat(detector.maskWithAsterisks(text)).as(text).hasSameSizeAs(text);
        }
    }

    @Test
    @DisplayName("[CHAT-GC-54] 마스킹은 탐지 구간 밖의 문자를 건드리지 않는다")
    void maskWithAsterisks_keepsCharactersOutsideSpans() {
        String text = "오늘 😀 개새끼 이겼다";

        String masked = detector.maskWithAsterisks(text);

        List<ProfanitySpan> spans = detector.detect(text);
        for (int i = 0; i < text.length(); i++) {
            int index = i;
            boolean inSpan = spans.stream().anyMatch(s -> index >= s.start() && index < s.end());
            assertThat(masked.charAt(i)).isEqualTo(inSpan ? '*' : text.charAt(i));
        }
    }
}
