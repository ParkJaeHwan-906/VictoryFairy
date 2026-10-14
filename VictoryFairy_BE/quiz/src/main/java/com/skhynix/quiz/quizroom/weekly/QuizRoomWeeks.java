package com.skhynix.quiz.quizroom.weekly;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 주간 베스트의 주 경계. 한 주는 KST 월요일 00:00 부터 다음 월요일 00:00 직전까지다.
 *
 * <p>입력 날짜는 반드시 KST 기준으로 구한 것이어야 한다({@code LocalDate.now(kstClock)}). 파드 JVM 은 UTC 라
 * {@code LocalDate.now()} 를 넘기면 월요일 00:00~09:00 KST 동안 아직 지난주로 판정된다.
 */
final class QuizRoomWeeks {

    private QuizRoomWeeks() {
    }

    /** {@code date} 가 속한 주의 월요일. */
    static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** {@code today} 바로 앞 주의 월요일 — 확정 대상은 언제나 이 주 하나다(2주 이상 밀린 주는 소급하지 않는다). */
    static LocalDate previousWeekStart(LocalDate today) {
        return weekStartOf(today).minusWeeks(1);
    }
}
