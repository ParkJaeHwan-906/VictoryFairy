package com.skhynix.quiz.chat.profanity;

import java.util.List;
import java.util.Map;

/**
 * 구단 {@code teams.code} 별 치환어 후보.
 *
 * <p>욕을 {@code ***} 가 아니라 응원 구단을 연상시키는 단어로 바꾸는 것이 이 기능의 제품적 목적이다.
 *
 * <p>⚠ <b>목록의 순서가 계약의 일부다.</b> 후보는 매칭된 원문 문자열의 해시로 고르므로, 순서를 바꾸거나
 * 단어를 추가하면 같은 욕설이 다른 단어로 치환되고 그 값을 고정한 테스트가 깨진다. 목록 변경은 테스트
 * 갱신을 동반하는 변경으로 취급할 것.
 *
 * <p>선택 키가 구단 이름이 아니라 {@code code} 인 이유는 이름은 py-collector 가 덮어쓸 수 있는 반면
 * {@code code} 는 UNIQUE 자연키이기 때문이다.
 */
final class MaskWordTable {

    private static final Map<String, List<String>> BY_TEAM_CODE = Map.of(
            "OB", List.of("두산", "망곰", "철웅이", "곰돌이"),
            "LG", List.of("엘지", "럭키", "스타", "쌍둥이"),
            "SS", List.of("삼성", "블레오", "사자"),
            "KT", List.of("케이티", "위즈", "마법사"),
            "WO", List.of("키움", "턱돌이", "히어로"),
            "HT", List.of("기아", "호랑이", "호걸이"),
            "HH", List.of("한화", "수리", "위니", "독수리"),
            "NC", List.of("엔씨", "단디", "쎄리", "공룡"),
            "LT", List.of("롯데", "누리", "아라", "거인"),
            "SK", List.of("에스에스지", "랜디", "쓱"));

    /**
     * 표에 없는 구단 code 의 폴백. 지금 시드로는 도달하지 않는 경로지만, 목록을 안 고친 채 구단이
     * 늘었을 때 마스킹이 조용히 건너뛰어져 욕설이 그대로 저장되는 것을 막는다.
     */
    private static final List<String> FALLBACK = List.of("야구", "직관", "응원");

    private MaskWordTable() {
    }

    /**
     * 매칭된 원문 문자열의 해시로 후보를 결정적으로 고른다 — 같은 구단·같은 욕설이면 파드가 달라도,
     * 재기동해도 항상 같은 단어다(무작위·시각·계정 id가 개입하지 않는다). {@code String.hashCode} 는
     * 명세로 고정된 값이라 JVM 이 달라도 결과가 갈리지 않는다.
     */
    static String pick(String teamCode, String matchedOriginal) {
        // teams.code 는 nullable 컬럼이라 null 로도 들어올 수 있다(Map.of 는 null 키 조회에 NPE 를 던진다).
        List<String> candidates = teamCode == null ? FALLBACK : BY_TEAM_CODE.getOrDefault(teamCode, FALLBACK);
        return candidates.get(Math.floorMod(matchedOriginal.hashCode(), candidates.size()));
    }
}
