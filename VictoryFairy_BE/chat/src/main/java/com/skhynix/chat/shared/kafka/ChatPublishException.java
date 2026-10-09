package com.skhynix.chat.shared.kafka;

/**
 * Kafka 발행이 제한 시간 안에 ack 되지 않았거나 실패했다. 의미는 호출부가 정한다.
 * 전송·신고는 503(CHAT-GC-62·82), 퇴장은 WARN 후 200(CHAT-GC-48).
 */
public class ChatPublishException extends RuntimeException {

    public ChatPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
