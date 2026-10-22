package com.skhynix.chat.shared.kafka;

/**
 * 토픽 이름. 앱은 토픽을 만들지 않는다(CHAT-GC-9) — 인프라가 미리 만들어 둔다.
 */
public final class ChatTopics {

    /** key = gameId. 한 방이 한 파티션에 들어가므로 레코드 오프셋이 곧 그 방의 msgId 다. */
    public static final String MESSAGES = "chat-messages";
    /** key = 툼스톤은 gameId, 종료 명령은 targetUserAccountId. */
    public static final String CONTROL = "chat-control";

    /** history-writer 의 고정 group id(CHAT-GC-96). 게이트웨이는 그룹 없이 assign 한다. */
    public static final String HISTORY_WRITER_GROUP = "chat-history-writer";

    private ChatTopics() {
    }
}
