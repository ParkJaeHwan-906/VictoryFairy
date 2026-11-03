package com.skhynix.chat.shared.kafka;

/**
 * chat-control 토픽의 value. {@code type} 으로 종류를 가른다 — 역직렬화는 {@link ChatKafkaCodec#readControl}.
 *
 * <p>소비자는 반드시 종류로 분기할 것. 종료 명령을 메시지 전달 경로로 흘리면 구독자에게 {@code data:} 로 샌다
 * (quiz 에서 이미 겪은 함정, CHAT-GC-92).
 */
public sealed interface ChatControlMessage permits BlindTombstone, SubscriptionCloseCommand {

    String type();

    /** 이 메시지를 발행할 때 쓰는 레코드 key. */
    String kafkaKey();
}
