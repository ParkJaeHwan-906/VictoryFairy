package com.skhynix.chat.shared.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@code chat:likes} 메시지의 JSON 변환. 발행(API)·구독(게이트웨이)이 같은 형식을 쓰도록 한 곳에 둔다. */
@Component
@RequiredArgsConstructor
public class LikeSignalCodec {

    private final ObjectMapper objectMapper;

    public String write(LikeSignal signal) {
        return objectMapper.writeValueAsString(signal);
    }

    /**
     * 레코드 바인딩 대신 트리로 읽는다 — 바인딩은 숫자 {@code teamCode} 를 문자열로 바꿔 받아 들여 형식 위반을
     * 통과시킨다(CHAT-LK-37).
     *
     * @throws IllegalArgumentException JSON 이 아니거나, gameId·teamCode 가 비어 있지 않은 문자열이 아니면
     */
    public LikeSignal read(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 이 아닌 chat:likes 메시지", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("객체가 아닌 chat:likes 메시지");
        }
        return new LikeSignal(requireText(root, "gameId"), requireText(root, "teamCode"));
    }

    private static String requireText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new IllegalArgumentException("chat:likes 메시지의 " + field + " 가 비어 있지 않은 문자열이 아니다");
        }
        return node.stringValue();
    }
}
