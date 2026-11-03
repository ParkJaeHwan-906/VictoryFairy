package com.skhynix.chat.room.service;

import com.skhynix.chat.shared.ChatRoles;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 방 수명 작업의 실행 시점: 매일 00:00 KST(CHAT-GC-16·17), API 파드 기동 직후 1회(CHAT-GC-19),
 * 실패 시 5분 간격 재시도(CHAT-GC-22).
 *
 * <p>zone 을 명시하지 않으면 파드(UTC) 기준 자정, 즉 09:00 KST 에 돈다(CHAT-GC-18).
 * 기동 시 실행은 스케줄러 스레드로 넘긴다. Redis 가 죽어 있어도 기동·readiness 를 붙잡지 않기 위해서다.
 */
@Component
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RoomLifecycleScheduler {

    static final Duration RETRY_INTERVAL = Duration.ofMinutes(5);

    private final RoomLifecycleJob job;
    private final TaskScheduler taskScheduler;

    private ScheduledFuture<?> pendingRetry;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void atMidnight() {
        attempt("midnight");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        taskScheduler.schedule(() -> attempt("startup"), Instant.now());
    }

    void attempt(String trigger) {
        try {
            job.run();
        } catch (Exception e) {
            log.error("채팅방 수명 작업 실패 trigger={}, {}분 뒤 재시도", trigger, RETRY_INTERVAL.toMinutes(), e);
            scheduleRetry();
        }
    }

    // 재시도는 한 번에 하나만 걸어 둔다. 실패가 겹쳐도 5분마다 한 번씩만 돈다.
    private synchronized void scheduleRetry() {
        if (pendingRetry != null && !pendingRetry.isDone()) {
            return;
        }
        pendingRetry = taskScheduler.schedule(() -> attempt("retry"), Instant.now().plus(RETRY_INTERVAL));
    }
}
