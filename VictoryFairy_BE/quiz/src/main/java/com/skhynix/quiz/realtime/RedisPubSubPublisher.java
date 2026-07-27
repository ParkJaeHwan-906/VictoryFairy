package com.skhynix.quiz.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 다중 인스턴스용 {@link RealtimeEventPublisher} 구현({@code prod} 전용). 이벤트를 Redis 채널로 발행하면
 * <b>발행한 인스턴스를 포함한</b> 모든 인스턴스의 {@link RealtimeEventSubscriber}가 받아 자기 레지스트리로
 * 넘긴다. 그래서 이 클래스는 로컬 레지스트리로 직접 전달하지 <b>않는다</b> — 직접 전달까지 하면 같은 파드
 * 구독자에게 두 번 간다.
 *
 * <p>파드가 늘면(HPA) 전송 POST를 받은 파드와 SSE 구독을 들고 있는 파드가 달라지는데, 이 경로가 없으면
 * 그 조합에서 메시지가 조용히 사라진다(에러 없이 "가끔 안 옴"). {@code !prod}는 파드가 하나뿐이므로
 * {@link InMemoryPublisher}로 충분하고 로컬 개발에 Redis를 요구하지 않는다.
 *
 * <p>전달은 포트 계약대로 fire-and-forget이다. 직렬화·발행 실패는 로그만 남기고 삼킨다 — 메시지는 이미
 * {@code chats}에 저장됐고 발신자에겐 201로 응답이 나갔으므로, 실시간 전달 실패가 그 성공을 되돌리면
 * 안 된다(수신자는 히스토리 조회로 복구한다).
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class RedisPubSubPublisher implements RealtimeEventPublisher {

    /**
     * 방마다 채널을 파지 않고 단일 채널에 {@code roomUid}를 실어 보낸다. 방별 채널은 구독/해지를 방 수명에
     * 맞춰 관리해야 하는데(구독자 0이 되는 시점마다 해지), 얻는 건 인스턴스별 필터링 비용 절약뿐이다.
     * 수신 측 {@link SseEmitterRegistry#publish}가 모르는 방이면 즉시 반환하므로 그 비용은 맵 조회 1회다.
     */
    static final String CHANNEL = "realtime:events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String roomUid, RealtimeEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(RealtimeEventMessage.of(roomUid, event));
            redisTemplate.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("실시간 이벤트 발행 실패 roomUid={} event={}", roomUid, event.name(), e);
        }
    }
}
