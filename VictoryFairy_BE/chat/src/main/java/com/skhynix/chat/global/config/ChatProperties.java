package com.skhynix.chat.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chat.*} 설정(CHAT-GC-11). 기본값은 application.yaml 이 단일 출처다. 환경변수(relaxed binding)로 덮어쓴다.
 */
@ConfigurationProperties("chat")
public record ChatProperties(
        Role role,
        History history,
        Recovery recovery,
        Gateway gateway,
        RateLimit rateLimit,
        Dedup dedup,
        Kafka kafka) {

    /**
     * 역할 플래그. API 를 켜고 게이트웨이를 끈 조합은 바인딩 단계에서 거부해 기동을 실패시킨다(CHAT-GC-6).
     * SseEmitterRegistry 가 파드 로컬이라, 구독을 받은 파드에 팬아웃 컨슈머가 없으면 그 구독자는 영원히 아무것도 못 받는다.
     */
    public record Role(boolean api, boolean gateway, boolean historyWriter) {

        public Role {
            if (api && !gateway) {
                throw new IllegalStateException(
                        "SSE 구독을 받는 파드는 게이트웨이 역할이 필요하다: chat.role.api=true 이면 chat.role.gateway 도 true 여야 한다");
            }
        }
    }

    public record History(int maxLen) {
    }

    public record Recovery(int batchSize, int maxBatches) {
    }

    public record Gateway(long batchIntervalMs, long writeTimeoutMs, int samplingThresholdPerSec) {
    }

    public record RateLimit(int perSecond) {
    }

    public record Dedup(long ttlSeconds) {
    }

    public record Kafka(long sendTimeoutMs) {
    }
}
