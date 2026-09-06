package com.skhynix.quiz.chat.profanity;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 금지어·예외·정규화·공백 엄격 데이터를 클래스패스 JSON 에서 읽는다(파이썬 {@code resources.py} 이식).
 *
 * <p>파이썬 쪽 검증도 함께 옮겼다. 특히 {@code single_char} 키의 한 글자 제약을 빼면
 * {@code "77": "ㄲ"} 같은 항목이 조용히 무시된 채 돌아간다 — 실패는 기동 시점에 드러나야 한다.
 */
@Component
public class ProfanityDataLoader {

    private static final String BASE_PATH = "profanity/";

    private final ObjectMapper objectMapper;

    public ProfanityDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ProfanityData load() {
        Map<String, List<String>> bannedWords = readBannedWords();
        List<String> exceptions = readList("exceptions.json");
        List<String> whitespaceStrict = readList("whitespace_strict.json");

        LinkedHashMap<String, LinkedHashMap<String, String>> normalization =
                read("normalization.json", new TypeReference<LinkedHashMap<String, LinkedHashMap<String, String>>>() {
                });
        Map<String, String> singleChar = normalization.getOrDefault("single_char", new LinkedHashMap<>());
        Map<String, String> multiChar = normalization.getOrDefault("multi_char", new LinkedHashMap<>());
        validateSingleChar(singleChar);

        return new ProfanityData(bannedWords, exceptions, singleChar, multiChar, whitespaceStrict);
    }

    private Map<String, List<String>> readBannedWords() {
        Map<String, List<String>> data =
                read("banned_words.json", new TypeReference<LinkedHashMap<String, List<String>>>() {
                });
        data.forEach((category, words) -> {
            if (words == null) {
                throw new IllegalStateException(
                        "banned_words.json 은 카테고리별 딕셔너리여야 합니다. 값이 비어 있는 카테고리: " + category);
            }
        });
        return data;
    }

    private void validateSingleChar(Map<String, String> singleChar) {
        // 파이썬 str.maketrans 제약을 그대로 옮긴다 — 여러 글자 치환은 multi_char 로 가야 하고
        // 그쪽이 먼저 적용된다(그래야 "77"→"ㄲ" 가 "7"→"t" 보다 앞선다).
        List<String> invalid = new ArrayList<>();
        for (String key : singleChar.keySet()) {
            if (key == null || key.codePointCount(0, key.length()) != 1) {
                invalid.add(String.valueOf(key));
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException(
                    "single_char 맵의 key 는 한 글자여야 합니다. 잘못된 항목: " + invalid + " (여러 글자는 multi_char 로 옮기세요)");
        }
    }

    private List<String> readList(String fileName) {
        return read(fileName, new TypeReference<ArrayList<String>>() {
        });
    }

    private <T> T read(String fileName, TypeReference<T> type) {
        ClassPathResource resource = new ClassPathResource(BASE_PATH + fileName);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("욕설 필터 데이터를 읽지 못했습니다: " + resource.getPath(), e);
        }
    }
}
