package com.skhynix.profanity;

/**
 * 탐지된 금지어의 원문 구간. 인덱스는 {@link String#substring(int, int)} 와 같은 규약이다
 * (UTF-16 code unit, {@code start} 포함·{@code end} 제외).
 *
 * @param start 원문 시작 인덱스(포함)
 * @param end 원문 끝 인덱스(제외)
 */
public record ProfanitySpan(int start, int end) {

    public int length() {
        return end - start;
    }
}
