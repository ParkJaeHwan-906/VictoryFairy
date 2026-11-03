package com.skhynix.chat.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이 모듈의 모든 "오늘"·"자정"·"지금" 판정의 단일 출처. 시간대는 Asia/Seoul 고정이다.
 *
 * <p>⚠ 파드 JVM 은 UTC 라 {@code LocalDate.now()}·{@code LocalDateTime.now()} 를 직접 쓰면 00:00~09:00 KST 사이에
 * 하루가 어긋나고, "다음 자정" 계산이 9시간 밀린다(quiz 의 kstClock 함정과 같은 계열). 시간이 필요한 곳은
 * 전부 이 컴포넌트를 거칠 것.
 */
@Component
public class ChatClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter ROOM_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 밀리초 3자리 + 오프셋 고정. ISO_OFFSET_DATE_TIME 은 나노초 자릿수가 값마다 달라져 형식이 흔들린다.
    private static final DateTimeFormatter SENT_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final Clock clock;

    @Autowired
    public ChatClock() {
        this(Clock.system(ZONE));
    }

    /** 테스트용 — 넘긴 Clock 의 존과 무관하게 판정은 Asia/Seoul 로 한다. */
    public ChatClock(Clock clock) {
        this.clock = clock.withZone(ZONE);
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** 오늘 00:00(KST 벽시계). games.game_date 반개구간 조회의 하한. */
    public LocalDateTime startOfToday() {
        return today().atStartOfDay();
    }

    /** 내일 00:00(KST 벽시계). games.game_date 반개구간 조회의 상한(미포함). */
    public LocalDateTime startOfTomorrow() {
        return today().plusDays(1).atStartOfDay();
    }

    /** {@code game_date}(KST 벽시계로 저장됨)가 오늘 경기인가. */
    public boolean isToday(LocalDateTime gameDate) {
        return gameDate != null && !gameDate.isBefore(startOfToday()) && gameDate.isBefore(startOfTomorrow());
    }

    /** 다음 00:00 KST 의 절대 시각. EXPIREAT 대상 — 절대 시각이라 여러 번 걸어도 멱등이다. */
    public Instant nextMidnight() {
        return today().plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    /** 다음 00:00 KST 까지 남은 초. 지연 생성 메타의 EX 값이며, 경계에서 0 이 되지 않게 최소 1 이다. */
    public long secondsUntilNextMidnight() {
        long seconds = nextMidnight().getEpochSecond() - clock.instant().getEpochSecond();
        return Math.max(1L, seconds);
    }

    /** chat:rooms:{yyyyMMdd} 의 날짜 부분. */
    public static String roomDate(LocalDate date) {
        return ROOM_DATE.format(date);
    }

    /** chat-messages 레코드의 sentAt — 서버 수신 시각, 오프셋 포함(예 2026-10-09T19:03:21.123+09:00). */
    public String sentAtNow() {
        return SENT_AT.format(now());
    }
}
