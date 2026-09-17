package com.skhynix.user.cleanup.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.user.cleanup.service.ExpiredDataCleanupService;
import com.skhynix.user.cleanup.support.CleanupExecutionLock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ExpiredDataCleanupScheduler}의 진입점 로직(기준 시각 계산·락 선점/해제·회차 위임)만 검증하는
 * 단위 테스트. 실제 {@code @Scheduled} 트리거 자체(크론 표현식 파싱, 실제 03:00 도달)는 스프링
 * 스케줄러 인프라의 책임이라 여기서 검증하지 않는다 — 이 클래스는 "그 순간이 오면 무엇을 하는가"만
 * 다룬다. 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 *
 * <p>{@code @InjectMocks} 대신 명시적으로 {@link Clock}을 고정 생성한다({@code GameServiceTest}·
 * {@code UserProfileEditServiceTest}와 같은 패턴) — 협력자를 셋 다 정확히 주입해야 하고, 새 협력자가
 * 추가되면 이 생성자 호출이 컴파일 에러로 먼저 알려준다({@code ExpiredDataCleanupService}가 협력자 3개
 * 라 조용한 NPE 함정을 이미 갖고 있다는 지시에 맞춰 생성자 주입을 명시했다).
 */
@ExtendWith(MockitoExtension.class)
class ExpiredDataCleanupSchedulerTest {

    // UTC 18:00 = KST 익일 03:00. USER-EDC-2 회귀 케이스 그대로 재사용 — 스케줄러가 baseTime을
    // Clock에서 딱 한 번 읽어 KST 벽시계로 넘기는지가 이 값 하나로 드러난다.
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-17T18:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime EXPECTED_BASE_TIME = LocalDateTime.of(2026, 8, 18, 3, 0, 0);

    @Mock
    private CleanupExecutionLock lock;

    @Mock
    private ExpiredDataCleanupService cleanupService;

    private ExpiredDataCleanupScheduler scheduler;

    private ExpiredDataCleanupScheduler schedulerWithFixedClock() {
        return new ExpiredDataCleanupScheduler(FIXED_CLOCK, lock, cleanupService);
    }

    @Test
    @DisplayName("[USER-EDC-2] UTC 18:00에 고정된 Clock에서 KST 03:00(익일)을 기준 시각으로 계산해 "
            + "정리 서비스에 그대로 넘긴다 — 운영 파드가 UTC라 시스템 기본 존을 쓰면 9시간 밀리는 지점")
    void removeExpiredData_computesKstBaseTimeFromUtcClock_andDelegatesSameValue() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.of("token"));

        // when
        scheduler.removeExpiredData();

        // then
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(cleanupService).removeExpiredData(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(EXPECTED_BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-24] 락 선점에 성공하면(Optional 값 있음) 정리 서비스를 호출한 뒤 같은 토큰으로 락을 해제한다")
    void removeExpiredData_lockAcquired_delegatesAndReleasesSameToken() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.of("token-abc"));

        // when
        scheduler.removeExpiredData();

        // then
        InOrder inOrder = inOrder(lock, cleanupService);
        inOrder.verify(lock).tryAcquire();
        inOrder.verify(cleanupService).removeExpiredData(any());
        inOrder.verify(lock).release("token-abc");
    }

    @Test
    @DisplayName("[USER-EDC-24, USER-EDC-25] 락이 이미 다른 파드에 선점돼 있으면(Optional 비어있음) "
            + "정리 서비스를 호출하지 않고 조용히 회차를 건너뛴다 — 예외를 던지지 않는다")
    void removeExpiredData_lockAlreadyHeld_skipsWithoutInvokingService() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.empty());

        // when
        scheduler.removeExpiredData();

        // then
        verifyNoInteractions(cleanupService);
    }

    @Test
    @DisplayName("락 획득 자체가 예외를 던지면 정리 서비스를 호출하지 않고 회차를 건너뛴다"
            + "(상호배제를 보장 못 하는 상태에서의 하드 삭제보다 하루 미루는 편이 안전하다는 설계 의도)")
    void removeExpiredData_lockAcquisitionThrows_skipsWithoutInvokingServiceOrPropagating() {
        // given
        scheduler = schedulerWithFixedClock();
        willThrow(new RuntimeException("redis down")).given(lock).tryAcquire();

        // when / then: 예외가 스케줄러 바깥으로 전파되지 않는다
        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.removeExpiredData())
                .doesNotThrowAnyException();
        verifyNoInteractions(cleanupService);
    }

    @Test
    @DisplayName("정리 서비스 실행 중 예외가 나도 락은 반드시 해제된다(finally) — 다음 회차까지 락이 묶여 "
            + "있으면 정상 파드까지 정리가 영영 멈춘다")
    void removeExpiredData_serviceThrows_stillReleasesLock() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.of("token-xyz"));
        willThrow(new RuntimeException("boom")).given(cleanupService).removeExpiredData(any());

        // when / then
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.removeExpiredData())
                .isInstanceOf(RuntimeException.class);
        verify(lock).release("token-xyz");
    }

    @Test
    @DisplayName("락 해제 자체가 실패해도(TTL이 회수) 예외가 호출자에게 전파되지 않는다")
    void removeExpiredData_lockReleaseThrows_doesNotPropagate() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.of("token-1"));
        willThrow(new RuntimeException("redis down")).given(lock).release("token-1");

        // when / then
        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.removeExpiredData())
                .doesNotThrowAnyException();
        verify(cleanupService).removeExpiredData(any());
    }

    @Test
    @DisplayName("락 미선점 시 release는 아예 호출되지 않는다 — 내가 잡지 않은 락을 지우려 시도하지 않는다")
    void removeExpiredData_lockNotAcquired_neverCallsRelease() {
        // given
        scheduler = schedulerWithFixedClock();
        given(lock.tryAcquire()).willReturn(Optional.empty());

        // when
        scheduler.removeExpiredData();

        // then
        verify(lock, never()).release(org.mockito.ArgumentMatchers.anyString());
    }
}
