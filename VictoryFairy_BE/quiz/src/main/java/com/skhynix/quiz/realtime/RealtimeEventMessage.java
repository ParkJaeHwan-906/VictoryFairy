package com.skhynix.quiz.realtime;

public record RealtimeEventMessage(String roomUid, String name, Object data, Long excludeUserAccountId) {

    public static RealtimeEventMessage of(String roomUid, RealtimeEvent event) {
        return new RealtimeEventMessage(roomUid, event.name(), event.data(), event.excludeUserAccountId());
    }

    public RealtimeEvent toEvent() {
        return new RealtimeEvent(name, data, excludeUserAccountId);
    }
}
