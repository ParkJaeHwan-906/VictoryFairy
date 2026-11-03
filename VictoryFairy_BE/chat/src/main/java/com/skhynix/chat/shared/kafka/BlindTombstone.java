package com.skhynix.chat.shared.kafka;

/**
 * 신고로 메시지를 숨기라는 툼스톤. key = gameId.
 * 게이트웨이는 그 방 구독자 전원에게 {@code deleted} 를, history-writer 는 blind 집합에 SADD 한다.
 */
public record BlindTombstone(String type, String gameId, long msgId) implements ChatControlMessage {

    public static final String TYPE = "blind";

    public static BlindTombstone of(String gameId, long msgId) {
        return new BlindTombstone(TYPE, gameId, msgId);
    }

    @Override
    public String kafkaKey() {
        return gameId;
    }
}
