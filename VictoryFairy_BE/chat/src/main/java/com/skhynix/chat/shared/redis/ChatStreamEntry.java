package com.skhynix.chat.shared.redis;

import com.skhynix.chat.shared.ChatMessageView;
import com.skhynix.chat.shared.kafka.ChatMessagePayload;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Stream {@code chat:game:{gameId}} 엔트리 한 건. history-writer(쓰기)와 히스토리·복구·신고(읽기)가
 * 같은 필드 규약을 쓰도록 변환을 한 곳에 둔다(CHAT-GC-97).
 *
 * <p>Redis 해시 필드는 null 을 담을 수 없어 null 값은 필드를 아예 빼고 쓴다. 읽을 때 없는 필드는 null 이다.
 *
 * @param senderId 내부용(차단 필터·본인 신고 판정). 외부로 내보내지 않는다
 */
public record ChatStreamEntry(
        long msgId,
        Long senderId,
        String senderNickname,
        String teamCode,
        String profileImgUrl,
        String content,
        String sentAt) {

    public static final String F_SENDER_ID = "senderId";
    public static final String F_SENDER_NICKNAME = "senderNickname";
    public static final String F_TEAM_CODE = "teamCode";
    public static final String F_PROFILE_IMG_URL = "profileImgUrl";
    public static final String F_CONTENT = "content";
    public static final String F_SENT_AT = "sentAt";

    public static ChatStreamEntry of(ChatMessagePayload payload, long offset) {
        return new ChatStreamEntry(offset, payload.senderId(), payload.senderNickname(), payload.teamCode(),
                payload.profileImgUrl(), payload.content(), payload.sentAt());
    }

    /** XADD 할 필드. 엔트리 id 는 {@link ChatRedisKeys#entryId(long)} 로 따로 준다. */
    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        putIfNotNull(fields, F_SENDER_ID, senderId == null ? null : String.valueOf(senderId));
        putIfNotNull(fields, F_SENDER_NICKNAME, senderNickname);
        putIfNotNull(fields, F_TEAM_CODE, teamCode);
        putIfNotNull(fields, F_PROFILE_IMG_URL, profileImgUrl);
        putIfNotNull(fields, F_CONTENT, content);
        putIfNotNull(fields, F_SENT_AT, sentAt);
        return fields;
    }

    public static ChatStreamEntry fromFields(String entryId, Map<?, ?> fields) {
        String senderId = asString(fields.get(F_SENDER_ID));
        return new ChatStreamEntry(
                ChatRedisKeys.msgIdOf(entryId),
                senderId == null ? null : Long.valueOf(senderId),
                asString(fields.get(F_SENDER_NICKNAME)),
                asString(fields.get(F_TEAM_CODE)),
                asString(fields.get(F_PROFILE_IMG_URL)),
                asString(fields.get(F_CONTENT)),
                asString(fields.get(F_SENT_AT)));
    }

    public ChatMessageView toView() {
        return new ChatMessageView(msgId, content, senderNickname, teamCode, profileImgUrl, sentAt);
    }

    private static void putIfNotNull(Map<String, String> fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
