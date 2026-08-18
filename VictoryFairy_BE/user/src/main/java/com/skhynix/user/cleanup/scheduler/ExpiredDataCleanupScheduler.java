package com.skhynix.user.cleanup.scheduler;

import com.skhynix.user.cleanup.service.ExpiredDataCleanupService;
import com.skhynix.user.cleanup.support.CleanupExecutionLock;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 한 번 만료 데이터 정리를 깨우는 유일한 지점. 실제 일은 전부
 * {@link ExpiredDataCleanupService} 가 하고, 여기서는 <b>언제 · 어느 파드가 · 어떤 시각 기준으로</b>
 * 도는지만 정한다.
 *
 * <p>{@code enabled} 가 참일 때만 빈으로 등록된다 — 꺼져 있으면 이 클래스도, 스케줄링 자체도
 * ({@code CleanupSchedulingConfig}) 존재하지 않는다. 기본값이 꺼짐인 이유는 로컬 개발 구성이
 * <b>원격 개발 DB 를 직접 보기</b> 때문이다. 켜져 있으면 로컬에서 앱을 띄워 둔 채 새벽 3시를 넘기는
 * 것만으로 원격 DB 의 계정이 실제로 지워진다(하드 삭제라 되돌릴 수 없다).
 *
 * <p>놓친 회차를 따라잡지 않는다({@code @Scheduled} 의 기본 동작). 03:00 에 앱이 안 떠 있었다면 그날은
 * 그냥 건너뛰고, 그 대상은 30일 조건을 여전히 만족한 채 다음 날 회차에 그대로 잡힌다 — 유실이 아니다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "user.cleanup.expired-data", name = "enabled", havingValue = "true")
public class ExpiredDataCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredDataCleanupScheduler.class);

    // KST 고정 클록(ClockConfig). 운영 파드는 UTC 로 돌기 때문에 LocalDateTime.now() 를 그대로 쓰면
    // 판정이 9시간 어긋나 경계에 걸친 계정을 하루 이르게/늦게 지운다.
    private final Clock clock;
    private final CleanupExecutionLock lock;
    private final ExpiredDataCleanupService cleanupService;

    @Scheduled(cron = "${user.cleanup.expired-data.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void removeExpiredData() {
        // 회차의 기준 시각은 여기서 딱 한 번 읽어 아래로 흘린다. 단계마다 다시 읽으면 계정 판정과
        // 토큰 판정이 서로 다른 시각을 보게 된다.
        LocalDateTime baseTime = LocalDateTime.now(clock);

        Optional<String> token;
        try {
            token = lock.tryAcquire();
        } catch (RuntimeException e) {
            // 락을 확인할 수 없으면 돌지 않는다 — 상호배제를 보장 못 하는 상태에서의 하드 삭제보다
            // 하루 미루는 편이 안전하다.
            log.error("정리 락 획득 실패 — 이번 회차를 건너뛴다: 기준 시각 {}", baseTime, e);
            return;
        }
        if (token.isEmpty()) {
            log.info("만료 데이터 정리 선점됨 — 다른 파드가 진행 중이라 이번 회차는 건너뛴다: 기준 시각 {}",
                    baseTime);
            return;
        }

        try {
            cleanupService.removeExpiredData(baseTime);
        } finally {
            try {
                lock.release(token.get());
            } catch (RuntimeException e) {
                // 놓아주지 못해도 TTL 이 회수한다 — 이 실패로 회차 결과가 달라지지는 않는다.
                log.warn("정리 락 해제 실패 — TTL 만료를 기다린다", e);
            }
        }
    }
}
