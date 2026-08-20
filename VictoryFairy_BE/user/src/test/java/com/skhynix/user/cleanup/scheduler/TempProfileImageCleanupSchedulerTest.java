package com.skhynix.user.cleanup.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.user.cleanup.service.TempProfileImageCleanupService;
import com.skhynix.user.cleanup.support.CleanupExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link TempProfileImageCleanupScheduler}의 진입점 로직(기준 시각 계산·락 선점/해제·회차 위임)만
 * 검증한다. 구조는 {@code ExpiredDataCleanupSchedulerTest}와 같다. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-80~92.
 */
@ExtendWith(MockitoExtension.class)
class TempProfileImageCleanupSchedulerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-17T19:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Seoul"));

    @Mock
    private CleanupExecutionLock lock;

    @Mock
    private TempProfileImageCleanupService cleanupService;

    private TempProfileImageCleanupScheduler schedulerWithFixedClock() {
        return new TempProfileImageCleanupScheduler(FIXED_CLOCK, lock, cleanupService);
    }

    @Test
    @DisplayName("[USER-PI-83] 기준 시각을 Clock에서 한 번 읽어 정리 서비스에 그대로 넘긴다")
    void removeExpiredTempImages_readsBaseTimeOnceFromClock_andDelegatesSameValue() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB))
                .willReturn(Optional.of("token"));

        // when
        scheduler.removeExpiredTempImages();

        // then
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(cleanupService).removeExpiredTempImages(captor.capture());
        assertThat(captor.getValue()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("[USER-PI-90] 락은 만료 데이터 정리와 다른 회차 이름(temp-profile-image)으로 선점된다"
            + " — 03:00 회차와 04:00 회차가 서로를 막지 않아야 하는 계약의 직접 증거")
    void removeExpiredTempImages_acquiresLockWithTempProfileImageJobName() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB))
                .willReturn(Optional.of("token"));

        // when
        scheduler.removeExpiredTempImages();

        // then
        verify(lock).tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB);
    }

    @Test
    @DisplayName("[USER-PI-90] 락 선점에 성공하면 정리 서비스를 호출한 뒤 같은 토큰·같은 회차 이름으로 락을 해제한다")
    void removeExpiredTempImages_lockAcquired_delegatesAndReleasesSameToken() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB))
                .willReturn(Optional.of("token-abc"));

        // when
        scheduler.removeExpiredTempImages();

        // then
        InOrder inOrder = inOrder(lock, cleanupService);
        inOrder.verify(lock).tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB);
        inOrder.verify(cleanupService).removeExpiredTempImages(any());
        inOrder.verify(lock).release(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB, "token-abc");
    }

    @Test
    @DisplayName("[USER-PI-90] 락이 이미 다른 파드에 선점돼 있으면 정리 서비스를 호출하지 않고 조용히 회차를 건너뛴다")
    void removeExpiredTempImages_lockAlreadyHeld_skipsWithoutInvokingService() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB)).willReturn(Optional.empty());

        // when
        scheduler.removeExpiredTempImages();

        // then
        verifyNoInteractions(cleanupService);
    }

    @Test
    @DisplayName("락 획득 자체가 예외를 던지면 정리 서비스를 호출하지 않고 회차를 건너뛴다(예외 전파 없음)")
    void removeExpiredTempImages_lockAcquisitionThrows_skipsWithoutInvokingServiceOrPropagating() {
        // given
        var scheduler = schedulerWithFixedClock();
        willThrow(new RuntimeException("redis down"))
                .given(lock).tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB);

        // when / then
        assertThatCode(scheduler::removeExpiredTempImages).doesNotThrowAnyException();
        verifyNoInteractions(cleanupService);
    }

    @Test
    @DisplayName("정리 서비스 실행 중 예외가 나도 락은 반드시 해제된다(finally)")
    void removeExpiredTempImages_serviceThrows_stillReleasesLock() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB))
                .willReturn(Optional.of("token-xyz"));
        willThrow(new RuntimeException("boom")).given(cleanupService).removeExpiredTempImages(any());

        // when / then: 예외는 그대로 전파되지만(finally 블록이라 삼키지 않는다) 락 해제는 반드시 일어난다
        assertThatThrownBy(scheduler::removeExpiredTempImages).isInstanceOf(RuntimeException.class);
        verify(lock).release(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB, "token-xyz");
    }

    @Test
    @DisplayName("락 미선점 시 release는 아예 호출되지 않는다")
    void removeExpiredTempImages_lockNotAcquired_neverCallsRelease() {
        // given
        var scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire(CleanupExecutionLock.TEMP_PROFILE_IMAGE_JOB)).willReturn(Optional.empty());

        // when
        scheduler.removeExpiredTempImages();

        // then
        verify(lock, never()).release(anyString(), anyString());
    }
}
