package com.skhynix.user.cleanup.scheduler;

import com.skhynix.user.cleanup.service.TempProfileImageCleanupService;
import com.skhynix.user.cleanup.support.CleanupExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 한 번 임시 프로필 이미지 정리를 깨우는 유일한 지점. 구조는
 * {@link ExpiredDataCleanupScheduler} 와 같다 — <b>언제 · 어느 파드가 · 어떤 시각 기준으로</b> 도는지만
 * 정하고 실제 일은 {@link TempProfileImageCleanupService} 가 한다.
 *
 * <p>기존 정리(03:00)와 <b>한 시간 띄운 04:00</b>이다. 같은 시각에 두면 락 경합과 부하가 겹친다.
 *
 * <p>실행 스위치가 만료 데이터 정리와 <b>별개</b>다({@code user.cleanup.temp-profile-image.enabled}).
 * 한쪽만 켠 환경에서도 켠 쪽은 반드시 돈다 — 스케줄링 활성화가 두 스위치의 OR 로 갈라져 있기
 * 때문이다({@code CleanupSchedulingConfig}).
 *
 * <p>기본값이 꺼짐인 이유도 그쪽과 같다: 로컬·dev 구성이 원격 자원을 직접 본다. 다만 이 잡이 지우는
 * 것은 DB 행이 아니라 <b>버킷의 temp 객체</b>라, 켠 채로 새벽 4시를 넘기면 개발 중이던 임시 이미지가
 * 사라진다(계정 데이터는 건드리지 않는다).
 *
 * <p>놓친 회차는 따라잡지 않는다. 04:30 에 기동해도 즉시 실행되지 않고, 대상 객체는 다음 날 회차에
 * 그대로 잡힌다 — 그마저 못 돌면 버킷 라이프사이클(temp/ 1일 만료)이 2차 안전망이다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "user.cleanup.temp-profile-image", name = "enabled",
        havingValue = "true")
public class TempProfileImageCleanupScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(TempProfileImageCleanupScheduler.class);

    // KST 고정 클록(ClockConfig). 판정에 쓰는 값이 UTC 인 lastModified 와 비교되는 Instant 라
    // 존 자체는 결과를 바꾸지 않지만, "지금"의 출처를 하나로 두는 규약을 여기서도 지킨다.
    private final Clock clock;
    private final CleanupExecutionLock lock;
    private final TempProfileImageCleanupService cleanupService;

    @Scheduled(cron = "${user.cleanup.temp-profile-image.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void removeExpiredTempImages() {
        // 회차의 기준 시각은 여기서 딱 한 번 읽어 아래로 흘린다.
        Instant baseTime = Instant.now(clock);

        Optional<String> token;
        try {
            token = lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB);
        } catch (RuntimeException e) {
            // 락을 확인할 수 없으면 돌지 않는다. 겹쳐도 삭제는 멱등이라 데이터 피해는 없지만,
            // 상호배제를 보장 못 하는 상태에서 굳이 돌 이유도 없다(하루 미뤄도 대상은 남는다).
            log.error("임시 프로필 이미지 정리 락 획득 실패 — 이번 회차를 건너뛴다: 기준 시각 {}",
                    baseTime, e);
            return;
        }
        if (token.isEmpty()) {
            log.info("임시 프로필 이미지 정리 선점됨 — 다른 파드가 진행 중이라 이번 회차는 "
                    + "건너뛴다: 기준 시각 {}", baseTime);
            return;
        }

        try {
            cleanupService.removeExpiredTempImages(baseTime);
        } finally {
            try {
                lock.release(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB, token.get());
            } catch (RuntimeException e) {
                // 놓아주지 못해도 TTL 이 회수한다 — 이 실패로 회차 결과가 달라지지는 않는다.
                log.warn("임시 프로필 이미지 정리 락 해제 실패 — TTL 만료를 기다린다", e);
            }
        }
    }
}
