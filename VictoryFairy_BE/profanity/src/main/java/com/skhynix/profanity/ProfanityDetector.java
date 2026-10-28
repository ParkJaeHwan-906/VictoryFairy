package com.skhynix.profanity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문에서 금지어 구간을 찾는 순수 컴포넌트. 상태도, DB 접근도, 외부 호출도 없다.
 *
 * <p>판정 로직은 {@code VictoryFairy_AI/validation/} 의 파이썬 구현을 이식한 것이며, 런타임에 그 앱을
 * 호출하지 않는다(전송은 고빈도 경로라 다른 앱의 가용성에 묶을 수 없다). 대가는 양쪽 데이터의 수동 동기화다.
 *
 * <p>파이썬과 갈라지는 지점: 그쪽은 유효/무효 판정이라 첫 매칭에서 멈추지만, 여기는 마스킹용이라
 * <b>모든 뷰·모든 카테고리의 매칭을 끝까지 모으고</b> 그 위치를 원문 좌표로 되돌린다.
 *
 * <p>무엇으로 치환할지는 소비자가 정한다 — quiz 는 구단 연상 단어로, 그 밖에는 {@link #maskWithAsterisks}.
 *
 * <p>패턴 컴파일은 생성 시 1회다. 데이터가 깨져 있으면 여기서 기동이 실패하는 편이 낫다 — 필터가
 * 조용히 꺼진 채 도는 것이 이 장치가 막으려는 바로 그 결과다.
 */
public class ProfanityDetector {

    private final TextNormalizer normalizer;
    private final ProfanityPatterns patterns;

    public ProfanityDetector(ProfanityDataLoader loader) {
        ProfanityData data = loader.load();
        this.normalizer = new TextNormalizer(
                data.singleCharNormalization(), data.multiCharNormalization());
        this.patterns = new ProfanityPatterns(data, normalizer);
    }

    /**
     * 원문에서 금지어 구간을 찾는다.
     *
     * @param content 사용자가 보낸 원문
     * @return 시작 위치 오름차순의 원문 구간 목록. 겹치는 매칭은 하나로 합쳐져 있고, 맞닿기만 한 구간은
     *         합치지 않는다({@code "시발시발"} 은 구간 두 개). 매칭이 없거나 입력이 null·빈 문자열이면 빈 목록
     */
    public List<ProfanitySpan> detect(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<int[]> spans = collectOriginSpans(content);
        if (spans.isEmpty()) {
            return List.of();
        }
        return merge(spans);
    }

    /**
     * 탐지 구간을 같은 길이의 {@code *} 로 바꾼다. 결과 길이는 원문 길이와 같다.
     *
     * @return 마스킹된 문자열. 매칭이 없으면 원문과 문자 단위로 동일하다(null·빈 문자열은 그대로)
     */
    public String maskWithAsterisks(String content) {
        List<ProfanitySpan> spans = detect(content);
        if (spans.isEmpty()) {
            return content;
        }
        char[] masked = content.toCharArray();
        for (ProfanitySpan span : spans) {
            for (int i = span.start(); i < span.end(); i++) {
                masked[i] = '*';
            }
        }
        return new String(masked);
    }

    /** 6종 뷰 전부에서 매칭을 모아 원문 구간 목록으로 되돌린다. */
    private List<int[]> collectOriginSpans(String content) {
        TracedText raw = TracedText.of(content);
        TracedText normalized = normalizer.normalize(raw);
        TracedText english = normalizer.latinOnly(normalized);

        List<MatchView> views = List.of(
                new MatchView(normalizer.rawView(raw), false),
                new MatchView(normalized, false),
                // 압축 뷰는 현재 정규화 뷰와 늘 같다(정규화 맵이 0~9 를 전부 문자로 치환하므로 숫자가 남지 않는다).
                // 그래도 지우지 말 것 — 숫자 매핑이 하나라도 빠지면 다시 독립 뷰가 된다.
                new MatchView(normalizer.digitsRemoved(normalized), false),
                new MatchView(normalizer.hangulOnly(normalized), false),
                new MatchView(english, false),
                new MatchView(KeyboardMapper.toHangul(english), true));

        List<int[]> spans = new ArrayList<>();
        for (MatchView view : views) {
            if (view.text().isEmpty()) {
                continue;
            }
            collectFromView(content, view, spans);
        }
        return spans;
    }

    private void collectFromView(String content, MatchView view, List<int[]> spans) {
        String value = view.text().value();
        List<int[]> exceptionSpans = findAll(patterns.exceptionPattern(), value);

        for (Pattern pattern : patterns.patternsFor(view.keyboardView())) {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                // 파이썬은 예외 표현을 문장에서 지운 뒤 검사하지만, 마스킹에서는 지우면 원문 좌표가
                // 또 어긋난다. 같은 뷰에서 겹치면 버리는 방식으로 같은 효과를 낸다.
                if (overlapsAny(exceptionSpans, matcher.start(), matcher.end())) {
                    continue;
                }
                int[] span = view.text().originSpan(matcher.start(), matcher.end());
                if (patterns.isWhitespaceStrict(matcher.group()) && hasWhitespace(content, span)) {
                    // 짧은 변형어(야발 등)는 정규화가 공백을 지워 생기는 오탐이 커서, 원문에서 붙어 있을 때만 본다
                    continue;
                }
                spans.add(span);
            }
        }
    }

    private static List<int[]> findAll(Pattern pattern, String value) {
        List<int[]> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            found.add(new int[]{matcher.start(), matcher.end()});
        }
        return found;
    }

    private static boolean overlapsAny(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (span[0] < end && start < span[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWhitespace(String content, int[] span) {
        for (int i = span[0]; i < span[1]; i++) {
            if (TextNormalizer.isWhitespace(content.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 겹치는 구간만 합친다. 맞닿기만 한 구간은 합치지 않는다 — quiz 에서 {@code "시발시발"} 은 치환어 두 개가 되어야 한다.
     */
    private static List<ProfanitySpan> merge(List<int[]> spans) {
        spans.sort(Comparator.<int[]>comparingInt(span -> span[0]).thenComparingInt(span -> span[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span[0] < last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new int[]{span[0], span[1]});
            }
        }
        List<ProfanitySpan> result = new ArrayList<>(merged.size());
        for (int[] span : merged) {
            result.add(new ProfanitySpan(span[0], span[1]));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 검사 대상 뷰 하나.
     *
     * @param keyboardView 키보드 뷰 여부 — 이 뷰만 완성형 음절 금지어로 대상을 좁힌다
     */
    private record MatchView(TracedText text, boolean keyboardView) {
    }
}
