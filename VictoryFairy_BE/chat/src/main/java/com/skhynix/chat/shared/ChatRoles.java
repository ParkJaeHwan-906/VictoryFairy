package com.skhynix.chat.shared;

/**
 * 역할 플래그의 프로퍼티 이름. 역할별 빈은 {@code @ConditionalOnBooleanProperty(name = ChatRoles.X, matchIfMissing = true)}
 * 로 켜고 끈다(기본값은 셋 다 켜짐 — CHAT-GC-2).
 *
 * <p>API 를 켜고 게이트웨이를 끈 조합은 {@code ChatProperties.Role} 바인딩에서 기동을 거부한다(CHAT-GC-6).
 */
public final class ChatRoles {

    /** REST + SSE 구독 + 방 수명 스케줄. */
    public static final String API = "chat.role.api";
    /** chat-messages·chat-control assign 컨슈머(팬아웃). */
    public static final String GATEWAY = "chat.role.gateway";
    /** 그룹 chat-history-writer 컨슈머(Redis Stream·blind 집합 적재). */
    public static final String HISTORY_WRITER = "chat.role.history-writer";

    private ChatRoles() {
    }
}
