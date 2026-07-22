package com.skhynix.quiz.realtime;

/**
 * 실시간 이벤트 전송 포트. 토픽은 방 식별자({@code roomUid})이며, 해당 방 구독자에게 이벤트를
 * fan-out 한다. 전달은 fire-and-forget으로, 발행 실패가 저장·전송 응답의 성공을 되돌리지 않는다.
 *
 * <p>기본 구현은 같은 프로세스 안에서 전달하는 {@link InMemoryPublisher}다(로컬·테스트·현재 단일
 * 인스턴스 운영에서 완결).
 *
 * <p>TODO(다중 인스턴스): 운영이 다중 인스턴스로 확장되면 이 포트의 Redis pub/sub 구현
 * ({@code RedisPubSubPublisher})을 추가해, 어느 인스턴스로 들어온 전송이든 모든 인스턴스의 해당 방
 * 구독자에게 닿게 한다. spring-data-redis 의존 도입은 email-validation의 redis 병합 시점에 함께 진행한다
 * (지금은 quiz에 redis 의존을 끌어오지 않는다).
 */
public interface RealtimeEventPublisher {

    void publish(String roomUid, RealtimeEvent event);
}
