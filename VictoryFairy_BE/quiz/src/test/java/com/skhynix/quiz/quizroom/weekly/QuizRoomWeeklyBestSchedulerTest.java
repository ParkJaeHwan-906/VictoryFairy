package com.skhynix.quiz.quizroom.weekly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.quiz.quizroom.weekly.QuizRoomWeeklyBestService.ConfirmResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class QuizRoomWeeklyBestSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate PREVIOUS_WEEK = LocalDate.of(2026, 10, 12);

    @Mock
    private QuizRoomWeeklyBestService weeklyBestService;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger schedulerLogger = (Logger) LoggerFactory.getLogger(QuizRoomWeeklyBestScheduler.class);

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        schedulerLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        schedulerLogger.detachAppender(logs);
    }

    private QuizRoomWeeklyBestScheduler schedulerAt(String utcInstant) {
        return new QuizRoomWeeklyBestScheduler(weeklyBestService, Clock.fixed(Instant.parse(utcInstant), KST));
    }

    @Test
    @DisplayName("[QUIZ-CQR-138] 월요일 00:10 KST(UTC 일요일 15:10)에 바로 앞 주(10/12) 하나만 확정한다")
    void confirmPreviousWeek_mondayZeroTenKst_confirmsJustFinishedWeek() {
        given(weeklyBestService.confirm(PREVIOUS_WEEK)).willReturn(new ConfirmResult(true, 3, 2));

        schedulerAt("2026-10-18T15:10:00Z").confirmPreviousWeek();

        verify(weeklyBestService).confirm(PREVIOUS_WEEK);
        verifyNoMoreInteractions(weeklyBestService);
    }

    @Test
    @DisplayName("[QUIZ-CQR-146] 2주 이상 밀린 10/28(수) KST 기동도 10/19 주 하나만 한 번 확정하고 10/12 주는 건드리지 않는다")
    void confirmPreviousWeek_wednesday_confirmsExactlyOneWeekOnce() {
        given(weeklyBestService.confirm(LocalDate.of(2026, 10, 19))).willReturn(new ConfirmResult(false, 0, 0));

        schedulerAt("2026-10-28T03:00:00Z").confirmPreviousWeek();

        verify(weeklyBestService, times(1)).confirm(any());
        verify(weeklyBestService).confirm(LocalDate.of(2026, 10, 19));
        verify(weeklyBestService, never()).confirm(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] 일요일 23:59:59 KST 에는 아직 주가 안 바뀌어 직전 주가 10/12 가 아니라 10/5 다")
    void confirmPreviousWeek_sundayLastSecond_stillPreviousOfCurrentWeek() {
        given(weeklyBestService.confirm(LocalDate.of(2026, 10, 5))).willReturn(new ConfirmResult(false, 0, 0));

        schedulerAt("2026-10-18T14:59:59Z").confirmPreviousWeek();

        verify(weeklyBestService).confirm(LocalDate.of(2026, 10, 5));
    }

    @Test
    @DisplayName("[QUIZ-CQR-143] 다른 인스턴스가 먼저 확정해 UNIQUE 위반이 나도 예외를 밖으로 내지 않고 INFO 로그만 남긴다")
    void confirmPreviousWeek_duplicateWeek_isSwallowedAsInfo() {
        given(weeklyBestService.confirm(PREVIOUS_WEEK)).willThrow(new DataIntegrityViolationException("uk_quiz_room_weeks_week_start"));

        assertThatCode(() -> schedulerAt("2026-10-18T15:10:00Z").confirmPreviousWeek()).doesNotThrowAnyException();

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("2026-10-12");
    }

    @Test
    @DisplayName("[QUIZ-CQR-144] 그 밖의 실패는 삼키되 ERROR 로 남긴다 (롤백됐으므로 다음 실행이 같은 주를 다시 확정한다)")
    void confirmPreviousWeek_otherFailure_isLoggedAsError() {
        given(weeklyBestService.confirm(PREVIOUS_WEEK)).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> schedulerAt("2026-10-18T15:10:00Z").confirmPreviousWeek()).doesNotThrowAnyException();

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    @Test
    @DisplayName("[QUIZ-CQR-143] 이미 확정된 주면 확정 로그를 남기지 않는다")
    void confirmPreviousWeek_alreadyConfirmed_noConfirmationLog() {
        given(weeklyBestService.confirm(PREVIOUS_WEEK)).willReturn(new ConfirmResult(false, 0, 0));

        schedulerAt("2026-10-18T15:10:00Z").confirmPreviousWeek();

        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("[QUIZ-CQR-138] 예약은 Asia/Seoul 기준의 월요일 cron 이다 (zone 을 빼면 파드 UTC 기준으로 월요일 09:00 KST 에 돈다)")
    void scheduledAnnotation_isKstMondayCron() throws NoSuchMethodException {
        Scheduled scheduled = QuizRoomWeeklyBestScheduler.class.getMethod("confirmPreviousWeek")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        assertThat(scheduled.cron()).endsWith(":0 1 * * * MON}");
    }

    // ---- 기동 시 ----

    @Test
    @DisplayName("[QUIZ-CQR-146] 기동 시 스케줄러의 확정 메서드를 정확히 한 번 호출한다")
    void startupRunner_delegatesToSchedulerOnce(@Mock QuizRoomWeeklyBestScheduler scheduler) {
        new QuizRoomWeeklyBestStartupRunner(scheduler).run(new DefaultApplicationArguments());

        verify(scheduler, times(1)).confirmPreviousWeek();
    }

    @Test
    @DisplayName("[QUIZ-CQR-146] 기동 시 확정은 설정이 없으면 켜져 있다 (matchIfMissing=true) - 파드가 내려가 있던 주를 메우는 기본 동작")
    void startupRunner_isEnabledByDefault() {
        ConditionalOnProperty condition = QuizRoomWeeklyBestStartupRunner.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.name()).containsExactly("quiz.room.weekly-best.on-startup");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isTrue();
    }
}
