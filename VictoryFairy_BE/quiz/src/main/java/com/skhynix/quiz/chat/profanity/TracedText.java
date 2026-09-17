package com.skhynix.quiz.chat.profanity;

/**
 * 변환된 문자열과 "각 문자가 원문 어느 인덱스에서 나왔는가"를 함께 들고 다니는 값 객체.
 *
 * <p>정규화는 문자를 지우고·합치고·늘리므로 뷰의 인덱스가 원문 인덱스와 어긋난다. 마스킹은 원문
 * 좌표를 지워야 하므로 변환 단계마다 출처를 이어 붙여야 한다.
 *
 * <p>출처를 인덱스 <b>집합</b>이 아니라 (min, max) 쌍으로 들고 있는 이유는, 되돌리기 규칙이
 * "기여 원문 인덱스의 최솟값~최댓값을 덮는 연속 구간"이라 집합의 중간 값이 결과에 영향을 주지
 * 않기 때문이다. 구간 합집합의 min/max 는 각 문자 min/max 의 min/max 와 같다.
 */
final class TracedText {

    private final String value;
    /** 각 문자에 기여한 원문 인덱스의 최솟값. */
    private final int[] originMin;
    /** 각 문자에 기여한 원문 인덱스의 최댓값(포함). */
    private final int[] originMax;

    private TracedText(String value, int[] originMin, int[] originMax) {
        this.value = value;
        this.originMin = originMin;
        this.originMax = originMax;
    }

    /** 아직 아무 변환도 거치지 않은 원문 — 각 문자의 출처는 자기 자신이다. */
    static TracedText of(String raw) {
        int[] min = new int[raw.length()];
        int[] max = new int[raw.length()];
        for (int i = 0; i < raw.length(); i++) {
            min[i] = i;
            max[i] = i;
        }
        return new TracedText(raw, min, max);
    }

    String value() {
        return value;
    }

    int length() {
        return value.length();
    }

    boolean isEmpty() {
        return value.isEmpty();
    }

    char charAt(int index) {
        return value.charAt(index);
    }

    int originMin(int index) {
        return originMin[index];
    }

    int originMax(int index) {
        return originMax[index];
    }

    /**
     * 뷰의 {@code [from, to)} 구간을 원문 구간 {@code [start, endExclusive)} 로 되돌린다.
     * 구간에 속한 문자들이 유래한 원문 인덱스의 최솟값부터 최댓값까지를 덮는 연속 구간이다.
     */
    int[] originSpan(int from, int to) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = from; i < to; i++) {
            min = Math.min(min, originMin[i]);
            max = Math.max(max, originMax[i]);
        }
        return new int[]{min, max + 1};
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private final StringBuilder value = new StringBuilder();
        private int[] min = new int[16];
        private int[] max = new int[16];
        private int size;

        /** 원문 인덱스 하나에서 나온 조각을 덧붙인다(1:N 확장 — {@code ㎏}→{@code kg}). */
        Builder append(String text, int originMin, int originMax) {
            for (int i = 0; i < text.length(); i++) {
                append(text.charAt(i), originMin, originMax);
            }
            return this;
        }

        Builder append(char ch, int originMin, int originMax) {
            ensureCapacity(size + 1);
            value.append(ch);
            min[size] = originMin;
            max[size] = originMax;
            size++;
            return this;
        }

        /** 원본 문자를 출처 그대로 옮긴다. */
        Builder copy(TracedText source, int index) {
            return append(source.charAt(index), source.originMin(index), source.originMax(index));
        }

        private void ensureCapacity(int required) {
            if (required <= min.length) {
                return;
            }
            int grown = Math.max(required, min.length * 2);
            int[] newMin = new int[grown];
            int[] newMax = new int[grown];
            System.arraycopy(min, 0, newMin, 0, size);
            System.arraycopy(max, 0, newMax, 0, size);
            min = newMin;
            max = newMax;
        }

        TracedText build() {
            int[] trimmedMin = new int[size];
            int[] trimmedMax = new int[size];
            System.arraycopy(min, 0, trimmedMin, 0, size);
            System.arraycopy(max, 0, trimmedMax, 0, size);
            return new TracedText(value.toString(), trimmedMin, trimmedMax);
        }
    }
}
