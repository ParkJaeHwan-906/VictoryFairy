package com.skhynix.quiz.chat.profanity;

import java.util.HashMap;
import java.util.Map;

/**
 * 두벌식 자판 역매핑(파이썬 {@code keyboard_to_hangul} 이식) — 한/영 키를 안 누르고 친 {@code "tlqkf"} 를
 * {@code "시발"} 로 복원한다.
 *
 * <p>Shift 조합(ㄲㅆㅃㅉㄸ·ㅒㅖ)은 소문자에서 구분할 수 없어 지원하지 않는다.
 *
 * <p>조합된 음절의 출처는 그 음절을 만든 자모들의 원문 인덱스 합집합이다. 받침이 다음 음절 초성으로
 * 넘어가는 경우가 있어 받침은 겹받침 두 조각을 따로 들고 있는다 — 합쳐 두면 넘어간 조각의 출처가
 * 앞 음절에도 남아 마스킹 구간이 원문 한 글자만큼 넓어진다.
 */
final class KeyboardMapper {

    private static final Map<Character, Character> LAYOUT = new HashMap<>();
    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] JONG = {"", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ",
            "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"};
    private static final Map<Character, Integer> JONG_INDEX = new HashMap<>();
    private static final Map<String, Character> VOWEL_COMBINE = new HashMap<>();
    private static final Map<String, Character> JONG_COMBINE = new HashMap<>();

    static {
        String keys = "qwertyuiopasdfghjklzxcvbnm";
        String jamos = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ";
        for (int i = 0; i < keys.length(); i++) {
            LAYOUT.put(keys.charAt(i), jamos.charAt(i));
        }
        for (int i = 0; i < JONG.length; i++) {
            if (!JONG[i].isEmpty()) {
                JONG_INDEX.put(JONG[i].charAt(0), i);
            }
        }
        VOWEL_COMBINE.put("ㅗㅏ", 'ㅘ');
        VOWEL_COMBINE.put("ㅗㅐ", 'ㅙ');
        VOWEL_COMBINE.put("ㅗㅣ", 'ㅚ');
        VOWEL_COMBINE.put("ㅜㅓ", 'ㅝ');
        VOWEL_COMBINE.put("ㅜㅔ", 'ㅞ');
        VOWEL_COMBINE.put("ㅜㅣ", 'ㅟ');
        VOWEL_COMBINE.put("ㅡㅣ", 'ㅢ');
        JONG_COMBINE.put("ㄱㅅ", 'ㄳ');
        JONG_COMBINE.put("ㄴㅈ", 'ㄵ');
        JONG_COMBINE.put("ㄴㅎ", 'ㄶ');
        JONG_COMBINE.put("ㄹㄱ", 'ㄺ');
        JONG_COMBINE.put("ㄹㅁ", 'ㄻ');
        JONG_COMBINE.put("ㄹㅂ", 'ㄼ');
        JONG_COMBINE.put("ㄹㅅ", 'ㄽ');
        JONG_COMBINE.put("ㄹㅌ", 'ㄾ');
        JONG_COMBINE.put("ㄹㅍ", 'ㄿ');
        JONG_COMBINE.put("ㄹㅎ", 'ㅀ');
        JONG_COMBINE.put("ㅂㅅ", 'ㅄ');
    }

    private KeyboardMapper() {
    }

    static TracedText toHangul(TracedText latin) {
        return new Session(latin).run();
    }

    /** 조합 상태(초성·중성·종성)와 각 조각의 출처를 함께 들고 도는 1회용 작업 단위. */
    private static final class Session {

        private final TracedText latin;
        private final TracedText.Builder out = TracedText.builder();

        private Slot lead;
        private Slot vowel;
        private Slot tailFirst;
        private Slot tailSecond;

        private Session(TracedText latin) {
            this.latin = latin;
        }

        private TracedText run() {
            for (int i = 0; i < latin.length(); i++) {
                Character jamo = LAYOUT.get(latin.charAt(i));
                if (jamo == null) {
                    continue;
                }
                Slot slot = new Slot(jamo, latin.originMin(i), latin.originMax(i));
                if (isConsonant(jamo)) {
                    acceptConsonant(slot);
                } else if (isVowel(jamo)) {
                    acceptVowel(slot);
                }
            }
            flush();
            return out.build();
        }

        private void acceptConsonant(Slot slot) {
            if (lead == null && vowel == null) {
                lead = slot;
            } else if (vowel == null) {
                // 자음+자음(모음 없음): 앞 자음 확정
                flush();
                lead = slot;
            } else if (tailFirst == null) {
                if (JONG_INDEX.containsKey(slot.jamo())) {
                    tailFirst = slot;
                } else {
                    flush();
                    lead = slot;
                }
            } else if (tailSecond == null && JONG_COMBINE.containsKey("" + tailFirst.jamo() + slot.jamo())) {
                tailSecond = slot;
            } else {
                flush();
                lead = slot;
            }
        }

        private void acceptVowel(Slot slot) {
            if (lead == null) {
                // 초성 없는 모음 → 낱자로 흘려보낸다
                out.append(slot.jamo(), slot.originMin(), slot.originMax());
                return;
            }
            if (vowel == null) {
                vowel = slot;
                return;
            }
            if (tailFirst == null) {
                Character combined = VOWEL_COMBINE.get("" + vowel.jamo() + slot.jamo());
                if (combined != null) {
                    vowel = new Slot(combined, Math.min(vowel.originMin(), slot.originMin()),
                            Math.max(vowel.originMax(), slot.originMax()));
                } else {
                    flush();
                    vowel = slot;
                }
                return;
            }
            // 받침 뒤 모음 → 받침(겹받침이면 뒷 조각)이 다음 음절 초성으로 이동
            Slot moved;
            if (tailSecond != null) {
                moved = tailSecond;
                tailSecond = null;
            } else {
                moved = tailFirst;
                tailFirst = null;
            }
            flush();
            lead = moved;
            vowel = slot;
        }

        /** 지금까지 모인 조각을 한 음절(또는 낱자)로 확정해 출력에 붙이고 상태를 비운다. */
        private void flush() {
            if (lead != null && vowel != null) {
                char tail = tailFirst == null ? 0
                        : (tailSecond == null ? tailFirst.jamo()
                                : JONG_COMBINE.get("" + tailFirst.jamo() + tailSecond.jamo()));
                int tailIndex = tail == 0 ? 0 : JONG_INDEX.getOrDefault(tail, 0);
                char syllable = (char) (0xAC00
                        + (CHO.indexOf(lead.jamo()) * 21 + JUNG.indexOf(vowel.jamo())) * 28 + tailIndex);
                int min = Math.min(lead.originMin(), vowel.originMin());
                int max = Math.max(lead.originMax(), vowel.originMax());
                if (tailFirst != null) {
                    min = Math.min(min, tailFirst.originMin());
                    max = Math.max(max, tailFirst.originMax());
                }
                if (tailSecond != null) {
                    min = Math.min(min, tailSecond.originMin());
                    max = Math.max(max, tailSecond.originMax());
                }
                out.append(syllable, min, max);
            } else {
                // 초성만·중성만 남은 상태는 낱자 그대로 흘려보낸다(파이썬 block() 의 else 분기)
                appendIfPresent(lead);
                appendIfPresent(vowel);
                appendIfPresent(tailFirst);
                appendIfPresent(tailSecond);
            }
            lead = null;
            vowel = null;
            tailFirst = null;
            tailSecond = null;
        }

        private void appendIfPresent(Slot slot) {
            if (slot != null) {
                out.append(slot.jamo(), slot.originMin(), slot.originMax());
            }
        }
    }

    private static boolean isConsonant(char jamo) {
        return CHO.indexOf(jamo) >= 0;
    }

    private static boolean isVowel(char jamo) {
        return JUNG.indexOf(jamo) >= 0;
    }

    private record Slot(char jamo, int originMin, int originMax) {
    }
}
