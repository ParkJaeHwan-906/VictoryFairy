package com.skhynix.quiz.realtime;

/**
 * 실시간 이벤트 전송 포트(토픽={@code roomUid}). fire-and-forget이라 발행 실패가 저장·전송 응답의
 * 성공을 되돌리지 않는다. 구현은 프로파일로 갈린다 — {@link InMemoryPublisher}(!prod) /
 * {@link RedisPubSubPublisher}(prod, HPA로 파드가 여러 개일 수 있어 인스턴스 간 버스가 필요).
 */
public interface RealtimeEventPublisher {

    void publish(String roomUid, RealtimeEvent event);
}
