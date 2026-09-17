package com.skhynix.quiz.realtime;

public interface RealtimeEventPublisher {

    void publish(String roomUid, RealtimeEvent event);
}
