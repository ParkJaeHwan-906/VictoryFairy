package com.skhynix.quiz.realtime;

public record RealtimeEvent(String name, Object data, Long excludeUserAccountId) {
}
