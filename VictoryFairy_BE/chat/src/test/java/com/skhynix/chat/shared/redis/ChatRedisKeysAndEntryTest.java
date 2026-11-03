package com.skhynix.chat.shared.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatRedisKeysAndEntryTest {

    @Test
    @DisplayName("[CHAT-GC-25] Redis 키 이름이 요구사항 표와 같다")
    void keyNames() {
        assertThat(ChatRedisKeys.rooms(LocalDate.of(2026, 10, 9))).isEqualTo("chat:rooms:20261009");
        assertThat(ChatRedisKeys.room("G1")).isEqualTo("chat:room:G1");
        assertThat(ChatRedisKeys.stream("G1")).isEqualTo("chat:game:G1");
        assertThat(ChatRedisKeys.blind("G1")).isEqualTo("chat:blind:G1");
        assertThat(ChatRedisKeys.dedup("G1", "abc")).isEqualTo("chat:dedup:G1:abc");
        assertThat(ChatRedisKeys.rate(7L)).isEqualTo("chat:rate:7");
    }

    @Test
    @DisplayName("[CHAT-GC-9] msgId(offset) 와 Stream 엔트리 id({offset}-0) 는 서로 변환된다")
    void entryIdConversion() {
        assertThat(ChatRedisKeys.entryId(4402L)).isEqualTo("4402-1");
        assertThat(ChatRedisKeys.msgIdOf("4402-1")).isEqualTo(4402L);
        assertThat(ChatRedisKeys.msgIdOf("4402")).isEqualTo(4402L);
    }

    @Test
    @DisplayName("[CHAT-GC-97] Stream 필드는 senderId·senderNickname·teamCode·profileImgUrl·content·sentAt 6개이고 msgId 는 필드가 아니다")
    void toFields_hasSixFields() {
        ChatMessagePayload payload = new ChatMessagePayload("G1", 7L, "닉", "OB", "img.jpg", "안녕", "t");

        Map<String, String> fields = ChatStreamEntry.of(payload, 100L).toFields();

        assertThat(fields).containsOnlyKeys("senderId", "senderNickname", "teamCode", "profileImgUrl", "content", "sentAt");
        assertThat(fields.get("senderId")).isEqualTo("7");
    }

    @Test
    @DisplayName("[CHAT-GC-97] null 값(teamCode·profileImgUrl)은 Redis 해시에 담을 수 없어 필드를 빼고 쓰고, 읽을 때 null 로 복원된다")
    void nullFields_areOmittedAndRestoredAsNull() {
        ChatMessagePayload payload = new ChatMessagePayload("G1", 7L, "닉", null, null, "안녕", "t");

        Map<String, String> fields = ChatStreamEntry.of(payload, 100L).toFields();
        ChatStreamEntry restored = ChatStreamEntry.fromFields("100-1", fields);

        assertThat(fields).doesNotContainKeys("teamCode", "profileImgUrl");
        assertThat(restored.teamCode()).isNull();
        assertThat(restored.profileImgUrl()).isNull();
        assertThat(restored.msgId()).isEqualTo(100L);
        assertThat(restored.senderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("[CHAT-GC-73] 외부 항목은 {msgId, content, senderNickname, teamCode, profileImgUrl, sentAt} 6필드이고 senderId 가 없다")
    void view_hasSixFieldsWithoutSenderId() {
        ChatStreamEntry entry = new ChatStreamEntry(5L, 7L, "닉", "OB", null, "안녕", "t");

        assertThat(entry.toView()).isEqualTo(new ChatMessageView(5L, "안녕", "닉", "OB", null, "t"));
        assertThat(ChatMessageView.class.getRecordComponents()).extracting("name")
                .containsExactly("msgId", "content", "senderNickname", "teamCode", "profileImgUrl", "sentAt");
    }
}
