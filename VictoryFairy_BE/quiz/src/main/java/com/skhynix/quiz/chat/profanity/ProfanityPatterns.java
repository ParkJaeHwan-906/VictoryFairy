package com.skhynix.quiz.chat.profanity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 카테고리별 금지어 패턴·키보드 뷰 전용 패턴·예외 표현 패턴을 앱 시작 시 1회만 컴파일해 재사용한다
 * (파이썬 {@code patterns.py} 이식). 전송은 고빈도 경로라 요청마다 컴파일하면 안 된다.
 */
final class ProfanityPatterns {

    /** 아무것도 매칭하지 않는 패턴 — 목록이 비었을 때 쓴다. */
    private static final Pattern MATCH_NOTHING = Pattern.compile("(?!x)x");
    private static final Pattern SYLLABLE_ONLY = Pattern.compile("^[가-힣]+$");

    private final Map<String, Pattern> categoryPatterns;
    private final Map<String, Pattern> keyboardPatterns;
    private final Pattern exceptionPattern;
    private final Set<String> whitespaceStrictWords;

    ProfanityPatterns(ProfanityData data, TextNormalizer normalizer) {
        Map<String, Pattern> categories = new LinkedHashMap<>();
        Map<String, Pattern> keyboards = new LinkedHashMap<>();
        data.bannedWords().forEach((category, words) -> {
            List<String> normalized = normalizeAll(words, normalizer);
            categories.put(category, compile(normalized));
            // 키보드 뷰는 영단어가 자판 복원되며 낱자 초성(ㅁㅊ·ㅗ)을 만들어 대량 오탐을 낸다.
            // 그래서 이 뷰에서는 완성형 음절 금지어만 본다.
            keyboards.put(category, compile(normalized.stream()
                    .filter(word -> SYLLABLE_ONLY.matcher(word).matches())
                    .toList()));
        });
        this.categoryPatterns = Collections.unmodifiableMap(categories);
        this.keyboardPatterns = Collections.unmodifiableMap(keyboards);
        this.exceptionPattern = compile(normalizeAll(data.exceptions(), normalizer));
        this.whitespaceStrictWords = Set.copyOf(
                new LinkedHashSet<>(normalizeAll(data.whitespaceStrictWords(), normalizer)));
    }

    /** 카테고리 이름은 결과에 쓰이지 않지만, 파이썬처럼 카테고리 단위로 나눠 검사한다. */
    Collection<Pattern> patternsFor(boolean keyboardView) {
        return keyboardView ? keyboardPatterns.values() : categoryPatterns.values();
    }

    Pattern exceptionPattern() {
        return exceptionPattern;
    }

    /** 이 단어는 원문에서 공백을 사이에 두면 매칭을 버린다(짧은 변형어 오탐 방지). */
    boolean isWhitespaceStrict(String normalizedWord) {
        return whitespaceStrictWords.contains(normalizedWord);
    }

    /**
     * 입력이 정규화된 뒤 매칭되므로 비교 기준인 단어도 같은 정규화를 거쳐야 한다. 정규화 결과가
     * 빈 문자열이 된 항목은 목록에서 뺀다(빈 패턴은 모든 위치에 매칭된다).
     */
    private static List<String> normalizeAll(List<String> words, TextNormalizer normalizer) {
        List<String> normalized = new ArrayList<>();
        for (String word : words) {
            if (word == null) {
                continue;
            }
            String value = normalizer.normalize(word);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    /**
     * 길이 내림차순으로 정렬해 교대(alternation)로 잇는다 — 같은 시작 위치에서는 먼저 적힌 대안이
     * 이기므로, 이 정렬이 "가장 긴 금지어를 고른다"는 규칙을 만든다({@code 새끼} 가 아니라 {@code 개새끼}).
     */
    private static Pattern compile(List<String> normalizedWords) {
        if (normalizedWords.isEmpty()) {
            return MATCH_NOTHING;
        }
        List<String> sorted = new ArrayList<>(normalizedWords);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder regex = new StringBuilder();
        for (String word : sorted) {
            if (!regex.isEmpty()) {
                regex.append('|');
            }
            regex.append(Pattern.quote(word));
        }
        return Pattern.compile(regex.toString());
    }
}
