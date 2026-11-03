package com.skhynix.chat.shared.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** chat:likes 메시지 변환(CHAT-LK-21·37). */
class LikeSignalCodecTest {

    private final LikeSignalCodec codec = new LikeSignalCodec(new ObjectMapper());

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("[CHAT-LK-21] 발행 메시지는 gameId 와 teamCode 두 필드뿐인 JSON 이다 (계정 id·닉네임·개수 없음)")
    void write_twoFieldsOnly() {
        String json = codec.write(new LikeSignal("20261009HTLG0", "HT"));

        assertThat(json).isEqualTo("{\"gameId\":\"20261009HTLG0\",\"teamCode\":\"HT\"}");
    }

    @Test
    @DisplayName("[CHAT-LK-21] 채널은 chat:likes 하나다")
    void channelName() {
        assertThat(LikeSignal.CHANNEL).isEqualTo("chat:likes");
    }

    @Test
    @DisplayName("[CHAT-LK-21] 쓴 메시지를 그대로 읽어 같은 값을 얻는다")
    void roundTrip() {
        LikeSignal read = codec.read(bytes(codec.write(new LikeSignal("G1", "LG"))));

        assertThat(read).isEqualTo(new LikeSignal("G1", "LG"));
    }

    @ParameterizedTest(name = "[{index}] 형식 위반 {0}")
    @ValueSource(strings = {
            "garbage",
            "",
            "   ",
            "null",
            "[]",
            "\"HT\"",
            "123",
            "{}",
            "{\"gameId\":\"G1\"}",
            "{\"teamCode\":\"HT\"}",
            "{\"gameId\":\"G1\",\"teamCode\":1}",
            "{\"gameId\":\"G1\",\"teamCode\":true}",
            "{\"gameId\":\"G1\",\"teamCode\":null}",
            "{\"gameId\":\"G1\",\"teamCode\":\"\"}",
            "{\"gameId\":\"G1\",\"teamCode\":\"   \"}",
            "{\"gameId\":\"G1\",\"teamCode\":[\"HT\"]}",
            "{\"gameId\":\"G1\",\"teamCode\":{\"a\":1}}",
            "{\"gameId\":5,\"teamCode\":\"HT\"}",
            "{\"gameId\":\"\",\"teamCode\":\"HT\"}",
            "{\"gameId\":\"G1\",\"teamCode\":\"HT\""
    })
    @DisplayName("[CHAT-LK-37] JSON 이 아니거나 gameId·teamCode 가 비어 있지 않은 문자열이 아니면 IllegalArgumentException 이다")
    void malformedMessagesRejected(String body) {
        assertThatThrownBy(() -> codec.read(bytes(body))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[CHAT-LK-37] 길이 0 바이트 배열도 IllegalArgumentException 이다 (다른 예외로 새지 않는다)")
    void emptyBytesRejected() {
        assertThatThrownBy(() -> codec.read(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[CHAT-LK-37] 필수 두 필드가 문자열이면 알 수 없는 필드가 더 있어도 받는다")
    void unknownExtraFieldsAreTolerated() {
        LikeSignal read = codec.read(bytes("{\"gameId\":\"G1\",\"teamCode\":\"OB\",\"extra\":1}"));

        assertThat(read).isEqualTo(new LikeSignal("G1", "OB"));
    }
}
