package com.skhynix.chat.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 파드 JVM 은 UTC 다. 00:00~09:00 KST(= 15:00~24:00 UTC) 구간에서 하루가 어긋나지 않는지가 핵심이다.
 */
class ChatClockTest {

    private static ChatClock at(String utcInstant) {
        // 일부러 UTC 존을 넘겨, 존이 달라도 판정이 Asia/Seoul 인지 확인한다
        return new ChatClock(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("[CHAT-GC-18] UTC 14:59:59(=23:59:59 KST)에는 오늘이 아직 UTC 날짜와 같은 날이다")
    void today_justBeforeKstMidnight_isSameDay() {
        ChatClock clock = at("2026-10-09T14:59:59Z");

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("[CHAT-GC-18] UTC 15:00:00(=00:00:00 KST)에는 오늘이 UTC 날짜보다 하루 앞선다")
    void today_atKstMidnight_isNextDayInKst() {
        ChatClock clock = at("2026-10-09T15:00:00Z");

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    @DisplayName("[CHAT-GC-18] UTC 00:30(=09:30 KST)처럼 UTC 날짜가 막 바뀐 시각에도 KST 날짜를 쓴다")
    void today_earlyUtcMorning_usesKstDate() {
        ChatClock clock = at("2026-10-10T00:30:00Z");

        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    @DisplayName("[CHAT-GC-18] UTC 15:30(=00:30 KST)에 다음 자정은 그 다음날 00:00 KST(=15:00 UTC)이다 — 9시간 어긋나지 않는다")
    void nextMidnight_afterKstMidnight_isFollowingDayFifteenUtc() {
        ChatClock clock = at("2026-10-09T15:30:00Z");

        assertThat(clock.nextMidnight()).isEqualTo(Instant.parse("2026-10-10T15:00:00Z"));
    }

    @Test
    @DisplayName("[CHAT-GC-97] UTC 14:00(=23:00 KST)에 다음 자정은 한 시간 뒤(15:00 UTC)이다")
    void nextMidnight_lateEvening_isOneHourAway() {
        ChatClock clock = at("2026-10-09T14:00:00Z");

        assertThat(clock.nextMidnight()).isEqualTo(Instant.parse("2026-10-09T15:00:00Z"));
        assertThat(clock.secondsUntilNextMidnight()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 지연 생성 메타의 EX 는 다음 00:00 KST 까지 남은 초이다")
    void secondsUntilNextMidnight_midday_isRemainingSeconds() {
        // 12:00 KST = 03:00 UTC → 12시간 남음
        ChatClock clock = at("2026-10-09T03:00:00Z");

        assertThat(clock.secondsUntilNextMidnight()).isEqualTo(12 * 3600L);
    }

    @Test
    @DisplayName("[CHAT-GC-107] 자정 직전 1초 남은 시각에는 1, 자정 정각 직후에는 하루치(86400)에 가까운 값이다")
    void secondsUntilNextMidnight_boundaries() {
        assertThat(at("2026-10-09T14:59:59Z").secondsUntilNextMidnight()).isEqualTo(1L);
        assertThat(at("2026-10-09T15:00:00Z").secondsUntilNextMidnight()).isEqualTo(86_400L);
    }

    @Test
    @DisplayName("[CHAT-GC-17] games.game_date 반개구간 하한·상한은 KST 벽시계 오늘 00:00 / 내일 00:00 이다")
    void startOfTodayAndTomorrow_areKstWallClock() {
        ChatClock clock = at("2026-10-09T15:30:00Z"); // 10-10 00:30 KST

        assertThat(clock.startOfToday()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 0));
        assertThat(clock.startOfTomorrow()).isEqualTo(LocalDateTime.of(2026, 10, 11, 0, 0));
    }

    @Test
    @DisplayName("[CHAT-GC-107] isToday 는 하한 포함·상한 미포함이며 null 은 false 이다")
    void isToday_boundaries() {
        ChatClock clock = at("2026-10-09T03:00:00Z"); // 10-09 12:00 KST

        assertThat(clock.isToday(LocalDateTime.of(2026, 10, 9, 0, 0))).isTrue();
        assertThat(clock.isToday(LocalDateTime.of(2026, 10, 9, 18, 30))).isTrue();
        assertThat(clock.isToday(LocalDateTime.of(2026, 10, 8, 23, 59, 59))).isFalse();
        assertThat(clock.isToday(LocalDateTime.of(2026, 10, 10, 0, 0))).isFalse();
        assertThat(clock.isToday(null)).isFalse();
    }

    @Test
    @DisplayName("[CHAT-GC-16] chat:rooms 키의 날짜 부분은 yyyyMMdd 이다")
    void roomDate_format() {
        assertThat(ChatClock.roomDate(LocalDate.of(2026, 1, 5))).isEqualTo("20260105");
    }

    @Test
    @DisplayName("[CHAT-GC-63] sentAt 은 밀리초 3자리 + +09:00 오프셋을 가진 ISO-8601 이다(UTC 파드에서도)")
    void sentAtNow_hasMillisAndKstOffset() {
        ChatClock clock = at("2026-10-09T10:03:21.123456Z"); // 19:03:21.123 KST

        assertThat(clock.sentAtNow()).isEqualTo("2026-10-09T19:03:21.123+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-63] sentAt 은 밀리초가 0 이어도 3자리를 유지한다")
    void sentAtNow_keepsThreeDigitMillis() {
        ChatClock clock = at("2026-10-09T10:03:21Z");

        assertThat(clock.sentAtNow()).isEqualTo("2026-10-09T19:03:21.000+09:00");
    }

    @Test
    @DisplayName("[CHAT-GC-18] 다른 존의 Clock 을 넘겨도 판정 존은 Asia/Seoul 로 고정된다")
    void constructor_forcesSeoulZone() {
        ChatClock clock = new ChatClock(Clock.fixed(Instant.parse("2026-10-09T15:00:00Z"), ZoneId.of("America/New_York")));

        assertThat(ChatClock.ZONE).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(clock.now().getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 10));
    }
}
