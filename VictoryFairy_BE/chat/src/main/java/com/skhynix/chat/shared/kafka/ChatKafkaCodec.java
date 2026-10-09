package com.skhynix.chat.shared.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka 레코드 value 의 JSON 변환. 프로듀서·컨슈머가 같은 형식을 쓰도록 한 곳에 둔다.
 *
 * <p>Kafka 직렬화기는 문자열(StringSerializer)이고 JSON 변환은 여기서 한다. 타입 헤더에 기대는 JSON 직렬화기를
 * 쓰면 레코드가 자바 클래스 이름에 묶인다.
 */
@Component
@RequiredArgsConstructor
public class ChatKafkaCodec {

    private final ObjectMapper objectMapper;

    public String write(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    public ChatMessagePayload readMessage(String json) {
        return objectMapper.readValue(json, ChatMessagePayload.class);
    }

    /**
     * @throws IllegalArgumentException 알 수 없는 {@code type}. 건너뛸지는 소비자가 정한다
     */
    public ChatControlMessage readControl(String json) {
        String type = objectMapper.readValue(json, TypeProbe.class).type();
        if (BlindTombstone.TYPE.equals(type)) {
            return objectMapper.readValue(json, BlindTombstone.class);
        }
        if (SubscriptionCloseCommand.TYPE.equals(type)) {
            return objectMapper.readValue(json, SubscriptionCloseCommand.class);
        }
        throw new IllegalArgumentException("알 수 없는 chat-control type: " + type);
    }

    private record TypeProbe(String type) {
    }
}
