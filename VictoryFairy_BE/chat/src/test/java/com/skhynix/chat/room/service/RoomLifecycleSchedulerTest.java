package com.skhynix.chat.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

class RoomLifecycleSchedulerTest {

    private final RoomLifecycleJob job = mock(RoomLifecycleJob.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final RoomLifecycleScheduler scheduler = new RoomLifecycleScheduler(job, taskScheduler);

    @Test
    @DisplayName("[CHAT-GC-18] 자정 작업의 cron 은 매일 00:00 이고 zone 이 Asia/Seoul 로 명시되어 있다(UTC 파드에서 09:00 KST 에 돌지 않는다)")
    void atMidnight_isScheduledAtMidnightInSeoulZone() throws Exception {
        Scheduled scheduled = RoomLifecycleScheduler.class.getMethod("atMidnight").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 0 0 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @Test
    @DisplayName("[CHAT-GC-19] 기동 완료 이벤트에서 작업을 스케줄러 스레드로 넘겨 1회 실행한다(기동을 붙잡지 않는다)")
    void onStartup_schedulesOneRunOnSchedulerThread() throws Exception {
        assertThat(RoomLifecycleScheduler.class.getMethod("onStartup").getAnnotation(EventListener.class)).isNotNull();

        scheduler.onStartup();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        verify(job, never()).run();
        task.getValue().run();
        verify(job).run();
    }

    @Test
    @DisplayName("[CHAT-GC-22] 작업이 실패해도 예외를 밖으로 던지지 않고 5분 뒤 재시도를 예약한다(기동을 막지 않는다)")
    void attempt_failure_schedulesRetryInFiveMinutes() {
        doThrow(new QueryTimeoutException("down")).when(job).run();
        Instant before = Instant.now();

        assertThatCode(() -> scheduler.attempt("midnight")).doesNotThrowAnyException();

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), at.capture());
        assertThat(at.getValue()).isBetween(before.plus(Duration.ofMinutes(5)), Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(RoomLifecycleScheduler.RETRY_INTERVAL).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("[CHAT-GC-22] 재시도가 대기 중일 때 실패가 겹쳐도 재시도는 하나만 예약한다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attempt_failureWhileRetryPending_doesNotScheduleSecondRetry() {
        ScheduledFuture pending = mock(ScheduledFuture.class);
        when(pending.isDone()).thenReturn(false);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(pending);
        doThrow(new QueryTimeoutException("down")).when(job).run();

        scheduler.attempt("midnight");
        scheduler.attempt("startup");

        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("[CHAT-GC-22] 이전 재시도가 끝났으면(isDone) 다음 실패에서 새 재시도를 예약한다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attempt_failureAfterRetryDone_schedulesAgain() {
        ScheduledFuture done = mock(ScheduledFuture.class);
        when(done.isDone()).thenReturn(true);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(done);
        doThrow(new QueryTimeoutException("down")).when(job).run();

        scheduler.attempt("retry");
        scheduler.attempt("retry");

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("[CHAT-GC-22] 작업이 성공하면 재시도를 예약하지 않는다")
    void attempt_success_schedulesNothing() {
        scheduler.attempt("midnight");

        verify(job).run();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
