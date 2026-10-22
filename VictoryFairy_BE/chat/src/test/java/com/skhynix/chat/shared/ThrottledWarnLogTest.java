package com.skhynix.chat.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** WARN 빈도 제한(CHAT-LK-52). 10초 규칙을 그대로 기다릴 수 없어 간격을 줄여 같은 성질을 본다. */
class ThrottledWarnLogTest {

    private static final Pattern COUNT = Pattern.compile("직전 로그 이후 (\\d+)건");

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("ThrottledWarnLogTest");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> lines() {
        return List.copyOf(appender.list);
    }

    private static long countOf(ILoggingEvent event) {
        Matcher matcher = COUNT.matcher(event.getFormattedMessage());
        assertThat(matcher.find()).as(event.getFormattedMessage()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    @Test
    @DisplayName("[CHAT-LK-52] 간격 안에 5천 번 기록해도 WARN 은 첫 1줄뿐이고, 간격이 지난 뒤 다음 기록에서 그동안의 건수가 한 줄로 실린다")
    void burstWithinInterval_oneLine_thenNextLineCarriesAccumulatedCount() throws Exception {
        ThrottledWarnLog warnLog = new ThrottledWarnLog(logger, "대기열 초과", 400, TimeUnit.MILLISECONDS);

        for (int i = 0; i < 5_000; i++) {
            warnLog.record(null);
        }

        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(countOf(lines().get(0))).isEqualTo(1L);

        Thread.sleep(450);
        warnLog.record(null);

        assertThat(lines()).hasSize(2);
        assertThat(countOf(lines().get(1))).as("직전 줄 이후 쌓인 4999 + 이번 1").isEqualTo(5_000L);
    }

    @Test
    @DisplayName("[CHAT-LK-52] 지속적으로 기록해도 줄 수는 (경과 시간 / 간격 + 1) 이하이고, 줄에 실린 건수의 합은 기록한 총건수를 넘지 않는다")
    void sustainedRecording_lineCountBoundedByInterval() throws Exception {
        long intervalMs = 250;
        ThrottledWarnLog warnLog = new ThrottledWarnLog(logger, "대기열 초과", intervalMs, TimeUnit.MILLISECONDS);
        AtomicLong recorded = new AtomicLong();

        long start = System.nanoTime();
        while (System.nanoTime() - start < 1_000_000_000L) {
            warnLog.record(null);
            recorded.incrementAndGet();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long upper = elapsedMs / intervalMs + 1;
        assertThat(lines().size()).isBetween(3, (int) upper);
        long summed = lines().stream().mapToLong(ThrottledWarnLogTest::countOf).sum();
        assertThat(summed).isLessThanOrEqualTo(recorded.get());
        assertThat(recorded.get() - summed).as("아직 줄에 못 실린 건수는 마지막 줄 이후 분량").isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("[CHAT-LK-52] cause 를 주면 줄에 원인 요약이 실리고, null 이면 스택 없이 문구만 남는다")
    void causeIsAppendedOnlyWhenGiven() {
        ThrottledWarnLog withCause = new ThrottledWarnLog(logger, "PUBLISH 실패", 10, TimeUnit.SECONDS);

        withCause.record(new IllegalStateException("boom"));

        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0).getFormattedMessage()).contains("PUBLISH 실패", "boom");
        assertThat(lines().get(0).getThrowableProxy()).as("스택을 싣지 않는다").isNull();
    }

    @Test
    @DisplayName("[CHAT-LK-52] 서로 다른 사유의 로그는 서로의 간격을 소모하지 않는다")
    void separateInstancesHaveSeparateIntervals() {
        ThrottledWarnLog a = new ThrottledWarnLog(logger, "사유 A", 10, TimeUnit.SECONDS);
        ThrottledWarnLog b = new ThrottledWarnLog(logger, "사유 B", 10, TimeUnit.SECONDS);

        a.record(null);
        a.record(null);
        b.record(null);

        assertThat(lines()).extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(2)
                .anyMatch(m -> m.contains("사유 A"))
                .anyMatch(m -> m.contains("사유 B"));
    }
}
