package com.skhynix.user.game.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.user.game.dto.GameResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link GameSseRegistry} 단위 테스트 — 갱신 한 건이 날짜 키와 홈·원정 구단 키에만 가고, 끊긴 구독은 빠진다.
 *
 * <p>{@link SseEmitter}는 응답이 열리기 전의 {@code send}를 버퍼에 두므로 실제 전송 내용은 볼 수 없다 —
 * spy 로 {@code send} 호출 횟수만 센다. 하트비트 스레드는 {@code @PostConstruct}라 여기서는 돌지 않는다.
 */
class GameSseRegistryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 1);

    private static GameResponse game(Long homeTeamId, Long awayTeamId) {
        return new GameResponse("20260801LGHT02026", "잠실야구장", "LG", homeTeamId, "KIA", awayTeamId,
                3, 5, LocalDateTime.of(2026, 8, 1, 18, 30), "IN_PROGRESS", null, 7, "TOP");
    }

    @Test
    @DisplayName("갱신은 그 날짜 구독자와 홈·원정 구단 구독자에게만 간다 — 다른 날짜·다른 구단은 받지 않는다")
    void publish_routesToDateAndBothTeamKeysOnly() throws IOException {
        GameSseRegistry registry = new GameSseRegistry();
        SseEmitter byDate = spy(new SseEmitter());
        SseEmitter byHome = spy(new SseEmitter());
        SseEmitter byAway = spy(new SseEmitter());
        SseEmitter otherTeam = spy(new SseEmitter());
        SseEmitter otherDate = spy(new SseEmitter());
        registry.register(GameSseRegistry.dateKey(DATE), byDate);
        registry.register(GameSseRegistry.teamKey(DATE, 3L), byHome);
        registry.register(GameSseRegistry.teamKey(DATE, 7L), byAway);
        registry.register(GameSseRegistry.teamKey(DATE, 9L), otherTeam);
        registry.register(GameSseRegistry.dateKey(DATE.plusDays(1)), otherDate);

        registry.publish(new GameUpdateEvent(game(3L, 7L), List.of("homeScore"), "2026-08-01T10:00:00Z"));

        verify(byDate, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(byHome, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(byAway, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherTeam, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherDate, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("전송이 실패한 구독은 레지스트리에서 빠진다 — 살아 있는 구독은 남는다")
    void publish_dropsBrokenEmitterOnly() throws IOException {
        GameSseRegistry registry = new GameSseRegistry();
        String key = GameSseRegistry.dateKey(DATE);
        SseEmitter broken = spy(new SseEmitter());
        doThrow(new IOException("closed")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        SseEmitter healthy = spy(new SseEmitter());
        registry.register(key, broken);
        registry.register(key, healthy);
        assertThat(registry.count(key)).isEqualTo(2);

        registry.publish(new GameUpdateEvent(game(3L, 7L), List.of("inning"), "2026-08-01T10:00:00Z"));

        // onCompletion 콜백은 서블릿 응답이 실제로 닫힐 때 컨테이너가 부른다 — 응답 없는 단위 테스트에서는
        // complete() 로 재현되지 않아 여기서는 전송 실패 경로만 본다.
        assertThat(registry.count(key)).isEqualTo(1);
        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("snapshot 은 등록과 무관하게 주어진 emitter 에 한 번 보낸다")
    void sendSnapshot_sendsOnce() throws IOException {
        GameSseRegistry registry = new GameSseRegistry();
        SseEmitter emitter = spy(new SseEmitter());

        registry.sendSnapshot(emitter, List.of(game(3L, 7L)));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("하트비트는 모든 구독에 주석 프레임을 보내고 실패한 구독을 걷어낸다")
    void heartbeat_pingsAllAndDropsBroken() throws IOException {
        GameSseRegistry registry = new GameSseRegistry();
        SseEmitter alive = spy(new SseEmitter());
        SseEmitter dead = spy(new SseEmitter());
        doThrow(new IOException("closed")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(GameSseRegistry.dateKey(DATE), alive);
        registry.register(GameSseRegistry.teamKey(DATE, 3L), dead);

        registry.heartbeat();

        verify(alive, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(registry.count(GameSseRegistry.teamKey(DATE, 3L))).isZero();
        assertThat(registry.count(GameSseRegistry.dateKey(DATE))).isEqualTo(1);
    }
}
