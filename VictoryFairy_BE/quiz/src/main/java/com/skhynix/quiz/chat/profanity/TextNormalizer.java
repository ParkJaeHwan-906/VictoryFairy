package com.skhynix.quiz.chat.profanity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * 텍스트 정규화 파이프라인(파이썬 {@code preprocess.py} 이식).
 *
 * <p>처리 순서 — ① 유니코드 호환 문자 접기 ② 소문자화 ③ 다중 문자 치환 ④ 단일 문자 치환
 * ⑤ 공백 제거 ⑥ 특수문자 제거. ③이 ④보다 먼저여야 {@code "77"→"ㄲ"} 가 {@code "7"→"t"} 보다 앞선다.
 *
 * <p>파이썬은 문자열만 넘기지만 여기서는 {@link TracedText} 로 출처를 함께 나른다 — 마스킹은
 * 뷰가 아니라 원문을 지워야 하기 때문이다.
 */
final class TextNormalizer {

    /** 파이썬 {@code \s} 에는 들어가지만 Java {@code Character.isWhitespace} 는 놓치는 NEL(U+0085). */
    private static final char NEXT_LINE = 0x0085;

    private static final int COMPAT_JAMO_START = 0x3131;
    private static final int COMPAT_JAMO_END = 0x3163;

    // --- 한글 조합(NFC 대체) 상수 ---
    private static final char L_BASE = 0x1100;
    private static final char V_BASE = 0x1161;
    private static final char T_BASE = 0x11A7;
    private static final char S_BASE = 0xAC00;
    private static final int L_COUNT = 19;
    private static final int V_COUNT = 21;
    private static final int T_COUNT = 28;
    private static final int S_COUNT = L_COUNT * V_COUNT * T_COUNT;

    private final Map<String, String> singleChar;
    private final Map<String, String> multiChar;

    TextNormalizer(Map<String, String> singleChar, Map<String, String> multiChar) {
        this.singleChar = singleChar;
        this.multiChar = multiChar;
    }

    /**
     * 파이썬 {@code \s}(str 기준)와 같은 공백 집합.
     *
     * <p>Java 정규식의 {@code \s}는 기본적으로 ASCII 공백만 보므로 전각 공백(U+3000)·NBSP 를 놓친다.
     * 그 차이를 두면 전각 공백을 쓴 문장에서만 AI 쪽과 판정이 갈린다. {@code isWhitespace}(U+001C~1F 포함,
     * NBSP 계열 제외) + {@code isSpaceChar}(Zs/Zl/Zp) + NEL 의 합집합이 파이썬의 집합과 일치한다.
     */
    static boolean isWhitespace(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == NEXT_LINE;
    }

    /** 목록(금지어·예외어) 비교 기준을 입력과 맞추기 위한 문자열 전용 정규화. */
    String normalize(String text) {
        return normalize(TracedText.of(text)).value();
    }

    TracedText normalize(TracedText input) {
        TracedText text = foldCompat(input);
        text = toLowerCase(text);
        text = replaceMultiChar(text);
        text = translateSingleChar(text);
        text = removeWhitespace(text);
        return removeSpecialChars(text);
    }

    /** 원문 뷰: 소문자화 후 공백만 제거한다(정규화·특수문자 제거를 거치지 않는다). */
    TracedText rawView(TracedText input) {
        return removeWhitespace(toLowerCase(input));
    }

    TracedText digitsRemoved(TracedText input) {
        return filter(input, ch -> ch < '0' || ch > '9');
    }

    TracedText hangulOnly(TracedText input) {
        return filter(input, TextNormalizer::isHangul);
    }

    TracedText latinOnly(TracedText input) {
        return filter(input, ch -> ch >= 'a' && ch <= 'z');
    }

    // --- 0단계: 유니코드 호환 문자 접기 -------------------------------------------------

    /**
     * 전각 {@code ｓｉｂａｌ}·수학 볼드·원문자 같은 겉보기만 다른 표기를 표준 문자로 접는다.
     *
     * <p>호환 자모(U+3131~U+3163)는 접기에서 뺀다 — NFKC 가 {@code ㅅ}(U+3145)을 조합용 자모(U+1109)로
     * 바꾸면 뒤의 특수문자 제거가 통째로 지워 초성 욕설이 무력화된다. 결합 분음부호를 떼기 위한 NFD 는
     * 한글 음절까지 자모로 분해하므로 완성형으로 되돌린다.
     */
    private TracedText foldCompat(TracedText input) {
        TracedText.Builder folded = TracedText.builder();
        // 코드포인트 단위로 돈다 — 수학 볼드 '𝘀𝗶𝗯𝗮𝗹'·프락투르 '𝖘𝖘𝖎𝖇𝖆𝖑' 은 BMP 밖이라
        // char 단위로 접으면 서러게이트 반쪽씩 넘겨 NFKC 가 아무 일도 못 하고 통과한다.
        forEachCodePoint(input, (codePoint, span) -> {
            String source = new String(Character.toChars(codePoint));
            folded.append(isCompatJamo(codePoint)
                    ? source
                    : Normalizer.normalize(source, Normalizer.Form.NFKC), span[0], span[1]);
        });
        return composeHangul(stripCombining(folded.build()));
    }

    private static boolean isCompatJamo(int codePoint) {
        return codePoint >= COMPAT_JAMO_START && codePoint <= COMPAT_JAMO_END;
    }

    /**
     * 결합 분음부호 제거({@code é} → {@code e}).
     *
     * <p>NFD 를 코드포인트 단위로 돌린다 — 정준 분해는 코드포인트별 고정 매핑이고, 문자 경계를 넘는 정준
     * 순서 차이는 뒤이어 결합 문자를 전부 버리므로 결과에 영향이 없다.
     *
     * <p>파이썬은 결합 클래스(≠0)로 거르지만 Java 는 그 값을 공개 API 로 노출하지 않아 일반 범주(Mn/Mc/Me)로
     * 거른다. 두 기준이 갈리는 문자(결합 클래스 0 인 마크)는 어차피 마지막 특수문자 제거에서 사라지므로
     * 판정 결과는 같다.
     */
    private TracedText stripCombining(TracedText input) {
        TracedText.Builder stripped = TracedText.builder();
        forEachCodePoint(input, (codePoint, span) -> {
            String decomposed = Normalizer.normalize(
                    new String(Character.toChars(codePoint)), Normalizer.Form.NFD);
            decomposed.codePoints().forEach(decomposedPoint -> {
                if (isCombining(decomposedPoint)) {
                    return;
                }
                stripped.append(new String(Character.toChars(decomposedPoint)), span[0], span[1]);
            });
        });
        return stripped.build();
    }

    private static boolean isCombining(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * NFD 가 분해한 한글을 완성형으로 되돌린다(파이썬의 마지막 NFC 단계).
     *
     * <p>결합 문자를 모두 걷어낸 뒤라 남은 문자는 전부 결합 클래스 0 이고, 그런 문자열에서 NFC 가 하는 일은
     * 한글 L+V(+T) 조합뿐이다. 그래서 알고리즘 조합만 직접 수행한다 — 문자열 전체 NFC 는 어느 입력 문자가
     * 어느 출력 문자를 만들었는지 알려주지 않아 출처 추적이 끊긴다.
     */
    private TracedText composeHangul(TracedText input) {
        TracedText.Builder composed = TracedText.builder();
        int i = 0;
        while (i < input.length()) {
            char ch = input.charAt(i);
            if (isLeadJamo(ch) && i + 1 < input.length() && isVowelJamo(input.charAt(i + 1))) {
                int syllable = S_BASE + ((ch - L_BASE) * V_COUNT + (input.charAt(i + 1) - V_BASE)) * T_COUNT;
                int consumed = 2;
                if (i + 2 < input.length() && isTailJamo(input.charAt(i + 2))) {
                    syllable += input.charAt(i + 2) - T_BASE;
                    consumed = 3;
                }
                composed.append((char) syllable, input.originMin(i), input.originMax(i + consumed - 1));
                i += consumed;
                continue;
            }
            if (isLvSyllable(ch) && i + 1 < input.length() && isTailJamo(input.charAt(i + 1))) {
                composed.append((char) (ch + input.charAt(i + 1) - T_BASE),
                        input.originMin(i), input.originMax(i + 1));
                i += 2;
                continue;
            }
            composed.copy(input, i);
            i++;
        }
        return composed.build();
    }

    private static boolean isLeadJamo(char ch) {
        return ch >= L_BASE && ch < L_BASE + L_COUNT;
    }

    private static boolean isVowelJamo(char ch) {
        return ch >= V_BASE && ch < V_BASE + V_COUNT;
    }

    private static boolean isTailJamo(char ch) {
        return ch > T_BASE && ch < T_BASE + T_COUNT;
    }

    private static boolean isLvSyllable(char ch) {
        return ch >= S_BASE && ch < S_BASE + S_COUNT && (ch - S_BASE) % T_COUNT == 0;
    }

    // --- 1~5단계 ----------------------------------------------------------------------

    private TracedText toLowerCase(TracedText input) {
        TracedText.Builder lowered = TracedText.builder();
        // 코드포인트 단위 소문자화 — 길이가 늘어나는 문자(U+0130 등)도 출처를 유지한 채 접힌다.
        forEachCodePoint(input, (codePoint, span) -> lowered.append(
                new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT), span[0], span[1]));
        return lowered.build();
    }

    /** 서러게이트 쌍을 쪼개지 않고 코드포인트 단위로 돈다(출처 구간을 함께 넘긴다). */
    private static void forEachCodePoint(TracedText input, CodePointConsumer consumer) {
        int i = 0;
        while (i < input.length()) {
            int codePoint = input.value().codePointAt(i);
            int width = Character.charCount(codePoint);
            consumer.accept(codePoint, spanOf(input, i, i + width));
            i += width;
        }
    }

    private TracedText replaceMultiChar(TracedText input) {
        TracedText text = input;
        for (Map.Entry<String, String> entry : multiChar.entrySet()) {
            text = replaceLiteral(text, entry.getKey(), entry.getValue());
        }
        return text;
    }

    private TracedText replaceLiteral(TracedText input, String source, String target) {
        if (source.isEmpty() || input.length() < source.length()) {
            return input;
        }
        TracedText.Builder replaced = TracedText.builder();
        int i = 0;
        while (i < input.length()) {
            if (input.value().startsWith(source, i)) {
                int end = i + source.length();
                int[] span = spanOf(input, i, end);
                replaced.append(target, span[0], span[1]);
                i = end;
            } else {
                replaced.copy(input, i);
                i++;
            }
        }
        return replaced.build();
    }

    /**
     * 단일 문자 치환. 한 번의 순회로 끝낸다(파이썬 {@code str.translate} 와 같은 성질) — 순차 replace 로
     * 풀면 치환 결과가 다른 규칙의 입력이 되어 연쇄 치환이 생긴다.
     */
    private TracedText translateSingleChar(TracedText input) {
        if (singleChar.isEmpty()) {
            return input;
        }
        TracedText.Builder translated = TracedText.builder();
        int i = 0;
        while (i < input.length()) {
            int codePoint = input.value().codePointAt(i);
            int width = Character.charCount(codePoint);
            String replacement = singleChar.get(new String(Character.toChars(codePoint)));
            int[] span = spanOf(input, i, i + width);
            if (replacement != null) {
                translated.append(replacement, span[0], span[1]);
            } else {
                for (int k = i; k < i + width; k++) {
                    translated.copy(input, k);
                }
            }
            i += width;
        }
        return translated.build();
    }

    private TracedText removeWhitespace(TracedText input) {
        return filter(input, ch -> !isWhitespace(ch));
    }

    /** 한글(자모 포함)·영문 소문자·숫자를 제외한 나머지를 삭제한다(치환이 아니라 제거). */
    private TracedText removeSpecialChars(TracedText input) {
        return filter(input, ch -> (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || isHangul(ch));
    }

    private static boolean isHangul(char ch) {
        return (ch >= 0x3131 && ch <= 0x3163) || (ch >= 0xAC00 && ch <= 0xD7A3);
    }

    private static TracedText filter(TracedText input, CharPredicate keep) {
        TracedText.Builder filtered = TracedText.builder();
        for (int i = 0; i < input.length(); i++) {
            if (keep.test(input.charAt(i))) {
                filtered.copy(input, i);
            }
        }
        return filtered.build();
    }

    private static int[] spanOf(TracedText input, int from, int to) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = from; i < to; i++) {
            min = Math.min(min, input.originMin(i));
            max = Math.max(max, input.originMax(i));
        }
        return new int[]{min, max};
    }

    @FunctionalInterface
    private interface CharPredicate {
        boolean test(char ch);
    }

    @FunctionalInterface
    private interface CodePointConsumer {
        void accept(int codePoint, int[] originSpan);
    }
}
