package com.skhynix.chat.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.skhynix.chat.global.config.ChatLikesProperties;
import com.skhynix.chat.like.service.ChatLikeService;
import com.skhynix.chat.like.service.LikeMetrics;
import com.skhynix.chat.like.service.LikePublisher;
import com.skhynix.chat.like.service.LikeRateLimiter;
import com.skhynix.chat.like.service.SupportTeamCodeCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/** 좋아요 판정 순서와 버림 사유별 메트릭. 협력 객체는 목, 속도 제한만 진짜를 쓰는 테스트가 있다. */
class ChatLikeServiceTest {

    private SupportTeamCodeCache teamCache;
    private LikeRateLimiter rateLimiter;
    private LikePublisher publisher;
    private SimpleMeterRegistry meters;
    private ChatLikeService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        teamCache = mock(SupportTeamCodeCache.class);
        rateLimiter = mock(LikeRateLimiter.class);
        publisher = mock(LikePublisher.class);
        meters = new SimpleMeterRegistry();
        service = new ChatLikeService(teamCache, rateLimiter, publisher, new LikeMetrics(meters));
        serviceLogger = (Logger) LoggerFactory.getLogger(ChatLikeService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    private double dropped(String reason) {
        return meters.get(LikeMetrics.DROPPED).tag("reason", reason).counter().count();
    }

    @Test
    @DisplayName("[CHAT-LK-2] 판정 순서는 응원 구단 조회 → 속도 제한 → 발행이다")
    void order_teamThenRateLimitThenPublish() {
        given(teamCache.teamCode(1L)).willReturn(Optional.of("HT"));
        given(rateLimiter.tryAcquire(1L)).willReturn(true);

        service.like("G1", 1L);

        InOrder inOrder = inOrder(teamCache, rateLimiter, publisher);
        inOrder.verify(teamCache).teamCode(1L);
        inOrder.verify(rateLimiter).tryAcquire(1L);
        inOrder.verify(publisher).publish("G1", "HT");
    }

    @Test
    @DisplayName("[CHAT-LK-41][CHAT-LK-10] 속도 제한을 통과한 좋아요는 경로의 gameId 와 그 사용자의 단일 구단 코드로 발행된다")
    void passes_publishesGameIdAndSingleTeamCode() {
        given(teamCache.teamCode(1L)).willReturn(Optional.of("HT"));
        given(rateLimiter.tryAcquire(1L)).willReturn(true);

        service.like("20261009HTLG0", 1L);

        verify(publisher).publish("20261009HTLG0", "HT");
        assertThat(dropped("rate-limit") + dropped("no-team") + dropped("team-lookup-failed") + dropped("queue-full"))
                .isZero();
    }

    @Test
    @DisplayName("[CHAT-LK-9] 응원 구단과 경기 구단이 달라도 그대로 발행한다 — 일치 여부를 보지 않는다")
    void teamMismatchIsNotChecked() {
        given(teamCache.teamCode(1L)).willReturn(Optional.of("HT"));
        given(rateLimiter.tryAcquire(1L)).willReturn(true);

        service.like("LGOB-game", 1L);

        verify(publisher).publish("LGOB-game", "HT");
    }

    @Test
    @DisplayName("[CHAT-LK-13] 응원 구단이 비어 있으면 발행도 속도 계수도 하지 않고, no-team +1 과 계정 id 가 담긴 WARN 을 남기며 예외 없이 끝난다")
    void noTeam_dropped_warnWithAccountId() {
        given(teamCache.teamCode(77L)).willReturn(Optional.empty());

        service.like("G1", 77L);

        verifyNoInteractions(publisher);
        verify(rateLimiter, never()).tryAcquire(anyLong());
        assertThat(dropped("no-team")).isEqualTo(1.0);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("77"));
    }

    @Test
    @DisplayName("[CHAT-LK-15] 응원 구단 조회가 예외로 실패하면 team-lookup-failed +1, 발행 없음, 예외는 밖으로 나가지 않는다")
    void lookupFailure_dropped() {
        given(teamCache.teamCode(5L)).willThrow(new IllegalStateException("DB down"));

        service.like("G1", 5L);

        verifyNoInteractions(publisher);
        verify(rateLimiter, never()).tryAcquire(anyLong());
        assertThat(dropped("team-lookup-failed")).isEqualTo(1.0);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("5"));
    }

    @Test
    @DisplayName("[CHAT-LK-16] 속도 제한에 걸리면 발행하지 않고 rate-limit +1 만 올린다 (예외 없음 — 호출부는 202)")
    void rateLimited_dropped() {
        given(teamCache.teamCode(1L)).willReturn(Optional.of("HT"));
        given(rateLimiter.tryAcquire(1L)).willReturn(false);

        service.like("G1", 1L);

        verify(publisher, never()).publish(anyString(), anyString());
        assertThat(dropped("rate-limit")).isEqualTo(1.0);
        assertThat(dropped("no-team")).isZero();
    }

    @Test
    @DisplayName("[CHAT-LK-23] 메트릭이 기동 시 전부 0 으로 등록되어 첫 증가 전에도 조회된다 (publish.failed 포함)")
    void metricsRegisteredAtZero() {
        assertThat(meters.get(LikeMetrics.PUBLISH_FAILED).counter().count()).isZero();
        for (String reason : new String[] {"rate-limit", "no-team", "team-lookup-failed", "queue-full"}) {
            assertThat(dropped(reason)).as(reason).isZero();
        }
    }

    @Test
    @DisplayName("[CHAT-LK-17] 속도 창은 경기를 가리지 않고 사용자 단위로 공유된다 — A경기 6회 + B경기 6회를 같은 초에 보내면 뒤의 2회가 버려진다")
    void rateWindowSharedAcrossGames() {
        LikeRateLimiter real = new LikeRateLimiter(new ChatLikesProperties(new ChatLikesProperties.RateLimit(10), 300, 100, 10_000));
        service = new ChatLikeService(teamCache, real, publisher, new LikeMetrics(meters));
        given(teamCache.teamCode(1L)).willReturn(Optional.of("HT"));

        for (int i = 0; i < 6; i++) {
            service.like("GAME-A", 1L);
        }
        for (int i = 0; i < 6; i++) {
            service.like("GAME-B", 1L);
        }

        verify(publisher, times(6)).publish("GAME-A", "HT");
        verify(publisher, times(4)).publish("GAME-B", "HT");
        assertThat(dropped("rate-limit")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("[CHAT-LK-16] 정상 경로는 예외 없이 끝난다")
    void happyPathDoesNotThrow() {
        given(teamCache.teamCode(any())).willReturn(Optional.of("HT"));
        given(rateLimiter.tryAcquire(any())).willReturn(true);

        service.like("G", 1L);

        verify(publisher).publish("G", "HT");
    }
}
