package com.skhynix.quiz.realtime;

/**
 * 실시간 이벤트 전송 포트. 토픽은 방 식별자({@code roomUid})이며, 해당 방 구독자에게 이벤트를
 * fan-out 한다. 전달은 fire-and-forget으로, 발행 실패가 저장·전송 응답의 성공을 되돌리지 않는다.
 *
 * <p>구현은 프로파일로 갈린다. 로컬·테스트({@code !prod})는 같은 프로세스 안에서 전달하는
 * {@link InMemoryPublisher}, 운영({@code prod})은 Redis 채널로 모든 인스턴스에 뿌리는
 * {@link RedisPubSubPublisher}다. 운영은 HPA로 파드가 여러 개가 될 수 있고, 그때 전송 POST를 받은
 * 파드와 SSE 구독을 들고 있는 파드가 달라지므로 인스턴스 간 버스가 없으면 메시지가 조용히 사라진다.
 */
public interface RealtimeEventPublisher {

    void publish(String roomUid, RealtimeEvent event);
}
