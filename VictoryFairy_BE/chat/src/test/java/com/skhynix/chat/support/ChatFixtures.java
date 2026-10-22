package com.skhynix.chat.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skhynix.chat.global.config.ChatProperties;
import com.skhynix.chat.shared.ChatClock;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.team.entity.Team;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.assertj.core.api.ThrowableAssert;

/** 엔티티는 생성자가 막혀 있어 Mockito 목으로 만든다(조회용 getter 만 쓴다). */
public final class ChatFixtures {

    /** 2026-10-09 12:00 KST. */
    public static final String NOON_KST_UTC = "2026-10-09T03:00:00Z";

    private ChatFixtures() {
    }

    public static ChatClock clockAt(String utcInstant) {
        return new ChatClock(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
    }

    public static Game game(String naverGameId, LocalDateTime gameDate, String state) {
        Game game = mock(Game.class);
        Team home = team(1L, "LG");
        Team away = team(2L, "두산");
        GameStatus status = mock(GameStatus.class);
        when(status.getName()).thenReturn(state);
        when(game.getNaverGameId()).thenReturn(naverGameId);
        when(game.getGameDate()).thenReturn(gameDate);
        when(game.getHomeTeam()).thenReturn(home);
        when(game.getAwayTeam()).thenReturn(away);
        when(game.getGameStatus()).thenReturn(status);
        return game;
    }

    private static Team team(Long id, String name) {
        Team team = mock(Team.class);
        when(team.getId()).thenReturn(id);
        when(team.getName()).thenReturn(name);
        return team;
    }

    /** application.yaml 의 기본값과 같은 설정. */
    public static ChatProperties props() {
        return props(150, 1000);
    }

    public static ChatProperties props(long batchIntervalMs, long writeTimeoutMs) {
        return new ChatProperties(
                new ChatProperties.Role(true, true, true),
                new ChatProperties.History(50_000),
                new ChatProperties.Recovery(500, 5),
                new ChatProperties.Gateway(batchIntervalMs, writeTimeoutMs, 0),
                new ChatProperties.RateLimit(3),
                new ChatProperties.Dedup(120),
                new ChatProperties.Kafka(3000));
    }

    /** BusinessException 이 특정 ErrorCode 인지. */
    public static void assertBusiness(ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
