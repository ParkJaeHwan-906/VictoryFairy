package com.skhynix.chat.shared.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatKafkaCodecTest {

    private final ChatKafkaCodec codec = new ChatKafkaCodec(new ObjectMapper());

    @Test
    @DisplayName("[CHAT-GC-63] 메시지 payload 는 직렬화 → 역직렬화로 7개 필드가 그대로 복원된다(null 필드 포함)")
    void messagePayload_roundTrips() {
        ChatMessagePayload original = new ChatMessagePayload("G1", 7L, "닉", null, null, "안녕",
                "2026-10-09T19:03:21.123+09:00");

        ChatMessagePayload restored = codec.readMessage(codec.write(original));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("[CHAT-GC-77] blind 툼스톤 JSON 은 BlindTombstone 으로 복원된다")
    void readControl_blind() {
        ChatControlMessage message = codec.readControl("{\"type\":\"blind\",\"gameId\":\"G1\",\"msgId\":4200}");

        assertThat(message).isEqualTo(new BlindTombstone("blind", "G1", 4200L));
    }

    @Test
    @DisplayName("[CHAT-GC-92] subscription-close JSON 은 SubscriptionCloseCommand 로 복원된다")
    void readControl_subscriptionClose() {
        ChatControlMessage message = codec.readControl(
                "{\"type\":\"subscription-close\",\"targetUserAccountId\":7,\"originInstanceId\":\"pod-a\","
                        + "\"allRooms\":false,\"gameId\":\"G1\"}");

        assertThat(message).isEqualTo(new SubscriptionCloseCommand("subscription-close", 7L, "pod-a", false, "G1"));
    }

    @Test
    @DisplayName("[CHAT-GC-92] 알 수 없는 type 의 제어 레코드는 IllegalArgumentException 이다(소비자가 건너뛴다)")
    void readControl_unknownType_throws() {
        assertThatThrownBy(() -> codec.readControl("{\"type\":\"mystery\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mystery");
    }

    @Test
    @DisplayName("[CHAT-GC-96] 깨진 JSON 은 예외를 던진다(소비자가 WARN 후 건너뛴다)")
    void readControl_malformedJson_throws() {
        assertThatThrownBy(() -> codec.readControl("not-json")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> codec.readMessage("not-json")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("[CHAT-GC-45] 퇴장 명령은 allRooms=false + gameId, 축출 명령은 allRooms=true + gameId=null 이다")
    void closeCommandFactories() {
        SubscriptionCloseCommand leave = SubscriptionCloseCommand.leave(7L, "pod-a", "G1");
        SubscriptionCloseCommand evict = SubscriptionCloseCommand.evict(7L, "pod-a");

        assertThat(leave.allRooms()).isFalse();
        assertThat(leave.gameId()).isEqualTo("G1");
        assertThat(evict.allRooms()).isTrue();
        assertThat(evict.gameId()).isNull();
        assertThat(leave.kafkaKey()).isEqualTo("7");
        assertThat(BlindTombstone.of("G1", 1L).kafkaKey()).isEqualTo("G1");
    }
}
