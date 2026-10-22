package com.skhynix.quiz.quizroom.weekly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주 경계는 KST 월요일 00:00 이다. 파드 JVM 이 UTC 여도 {@code LocalDate.now(kstClock)} 를 넘기는 한 같다. */
class QuizRoomWeeksTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static LocalDate kstDateAt(String utcInstant) {
        return LocalDate.now(Clock.fixed(Instant.parse(utcInstant), KST));
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] 월요일은 자기 자신이 주의 시작이다")
    void weekStartOf_monday_isItself() {
        assertThat(QuizRoomWeeks.weekStartOf(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] 일요일은 6일 전 월요일 주에 속한다 (다음 월요일 직전까지가 한 주)")
    void weekStartOf_sunday_belongsToPreviousMonday() {
        assertThat(QuizRoomWeeks.weekStartOf(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(QuizRoomWeeks.weekStartOf(LocalDate.of(2026, 10, 18)).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] 연말·연초를 걸쳐도 월요일로 정확히 돌아간다 (2027-01-01 금요일 -> 2026-12-28)")
    void weekStartOf_crossesYearBoundary() {
        assertThat(QuizRoomWeeks.weekStartOf(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] KST 일요일 23:59:59(UTC 14:59:59)는 10/12 주, KST 월요일 00:00:00(UTC 15:00:00)는 10/19 주다")
    void weekBoundary_isKstMondayMidnight_notUtc() {
        LocalDate sundayLastSecond = kstDateAt("2026-10-18T14:59:59Z");
        LocalDate mondayFirstSecond = kstDateAt("2026-10-18T15:00:00Z");

        assertThat(QuizRoomWeeks.weekStartOf(sundayLastSecond)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(QuizRoomWeeks.weekStartOf(mondayFirstSecond)).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    @DisplayName("[QUIZ-CQR-133] UTC 날짜로 판정하면 월요일 00:00~09:00 KST 동안 한 주 늦다 - 그래서 호출자는 KST Clock 의 날짜를 넘겨야 한다")
    void utcDate_wouldBeOneWeekLate_duringMondayMorningKst() {
        Instant mondayThreeAmKst = Instant.parse("2026-10-18T18:00:00Z");
        LocalDate utcDate = LocalDate.now(Clock.fixed(mondayThreeAmKst, ZoneId.of("UTC")));
        LocalDate kstDate = LocalDate.now(Clock.fixed(mondayThreeAmKst, KST));

        assertThat(QuizRoomWeeks.weekStartOf(utcDate)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(QuizRoomWeeks.weekStartOf(kstDate)).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    @DisplayName("[QUIZ-CQR-138, QUIZ-CQR-146] 바로 앞 주는 오늘이 속한 주의 월요일에서 7일 전이다")
    void previousWeekStart_isSevenDaysBeforeCurrentWeekStart() {
        assertThat(QuizRoomWeeks.previousWeekStart(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(QuizRoomWeeks.previousWeekStart(LocalDate.of(2026, 10, 25))).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(QuizRoomWeeks.previousWeekStart(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    @DisplayName("[QUIZ-CQR-138] 월요일 00:10 KST 에 바로 앞 주는 방금 끝난 주다")
    void previousWeekStart_atMondayZeroTen_isJustFinishedWeek() {
        LocalDate today = kstDateAt("2026-10-18T15:10:00Z");

        assertThat(QuizRoomWeeks.previousWeekStart(today)).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
