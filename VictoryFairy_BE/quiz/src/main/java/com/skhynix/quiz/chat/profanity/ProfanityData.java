package com.skhynix.quiz.chat.profanity;

import java.util.List;
import java.util.Map;

/**
 * 필터가 쓰는 4종 데이터의 적재 결과.
 *
 * <p>전부 {@code quiz/src/main/resources/profanity/} 의 JSON 이며, 앞 3종은
 * {@code VictoryFairy_AI/validation/core/data/} 와 같은 내용을 유지해야 한다(두 목록이 갈리면
 * 같은 문장이 채팅에서는 걸리고 AI 검증에서는 통과한다).
 *
 * @param bannedWords 카테고리 → 금지어 목록(입력 순서 유지)
 * @param exceptions 오탐 방지용 정상 표현
 * @param singleCharNormalization 한 글자 → 표준 문자 치환 맵
 * @param multiCharNormalization 여러 글자 → 표준 문자 치환 맵(단일 치환보다 먼저 적용된다)
 * @param whitespaceStrictWords 원문에서 공백을 사이에 두면 매칭을 버리는 단어 목록
 */
record ProfanityData(
        Map<String, List<String>> bannedWords,
        List<String> exceptions,
        Map<String, String> singleCharNormalization,
        Map<String, String> multiCharNormalization,
        List<String> whitespaceStrictWords) {
}
