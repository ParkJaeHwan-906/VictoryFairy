package com.skhynix.chat.like.service;

import com.skhynix.chat.shared.ChatRoles;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * 좋아요 경로 메트릭. 이름·태그는 요구사항 "메트릭" 표 그대로다. 기동 시 전부 등록해 0 부터 보이게 한다
 * (처음 증가할 때 등록하면 그 전까지 {@code /actuator/metrics/...} 가 404 다).
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
public class LikeMetrics {

    public static final String DROPPED = "chat.likes.dropped";
    public static final String PUBLISH_FAILED = "chat.likes.publish.failed";

    private final Counter rateLimited;
    private final Counter noTeam;
    private final Counter teamLookupFailed;
    private final Counter queueFull;
    private final Counter publishFailed;

    public LikeMetrics(MeterRegistry meterRegistry) {
        this.rateLimited = dropped(meterRegistry, "rate-limit");
        this.noTeam = dropped(meterRegistry, "no-team");
        this.teamLookupFailed = dropped(meterRegistry, "team-lookup-failed");
        this.queueFull = dropped(meterRegistry, "queue-full");
        this.publishFailed = Counter.builder(PUBLISH_FAILED)
                .description("실패한 chat:likes PUBLISH 횟수")
                .register(meterRegistry);
    }

    private static Counter dropped(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(DROPPED)
                .description("발행하지 않고 버린 좋아요 수")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    void rateLimited() {
        rateLimited.increment();
    }

    void noTeam() {
        noTeam.increment();
    }

    void teamLookupFailed() {
        teamLookupFailed.increment();
    }

    void queueFull() {
        queueFull.increment();
    }

    void publishFailed() {
        publishFailed.increment();
    }
}
