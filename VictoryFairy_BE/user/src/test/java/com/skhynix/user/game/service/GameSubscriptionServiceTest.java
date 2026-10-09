package com.skhynix.user.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.realtime.GameSseRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link GameSubscriptionService} 단위 테스트 — 구독 키·snapshot 내용·날짜 기본값. 레지스트리는 목이다.
 */
@ExtendWith(MockitoExtension.class)
class GameSubscriptionServiceTest {

    /** KST 2026-08-02 00:30 = UTC 2026-08-01 15:30 — 시스템 존을 따르면 하루 전이 되는 시각. */
    private static final Clock KST_AFTER_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-08-01T15:30:00Z"), ZoneId.of("Asia/Seoul"));

    private static final GameResponse SAMPLE = new GameResponse("20260801LGHT02026", "잠실야구장", "LG", 3L,
            "KIA", 7L, null, null, LocalDateTime.of(2026, 8, 1, 18, 30), "SCHEDULED", null, null, null);

    @Mock
    private GameService gameService;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private GameSseRegistry registry;

    @Mock
    private UserSupportTeam supportTeam;

    @Mock
    private Team team;

    private GameSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new GameSubscriptionService(gameService, gameRepository, userSupportTeamRepository, registry,
                KST_AFTER_MIDNIGHT);
    }

    @Test
    @DisplayName("날짜 구독: 그 날짜 키로 등록하고 GET /games 와 같은 목록을 snapshot 으로 보낸다")
    void subscribe_registersDateKey_andSendsSnapshot() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        SseEmitter emitter = new SseEmitter();
        given(gameService.getGames(date)).willReturn(List.of(SAMPLE));
        given(registry.register(GameSseRegistry.dateKey(date))).willReturn(emitter);

        SseEmitter result = service.subscribe(date);

        assertThat(result).isSameAs(emitter);
        verify(registry).sendSnapshot(emitter, List.of(SAMPLE));
    }

    @Test
    @DisplayName("date 를 생략하면 Asia/Seoul 기준 오늘로 구독한다 — UTC 로는 전날인 시각에도")
    void subscribe_missingDate_usesSeoulToday() {
        LocalDate seoulToday = LocalDate.of(2026, 8, 2);
        given(gameService.getGames(seoulToday)).willReturn(List.of());
        given(registry.register(GameSseRegistry.dateKey(seoulToday))).willReturn(new SseEmitter());

        service.subscribe(null);

        verify(gameService).getGames(seoulToday);
        verify(registry).register(GameSseRegistry.dateKey(seoulToday));
    }

    @Test
    @DisplayName("응원 구단 구독: 날짜+구단 키로 등록하고 그 구단 경기만 snapshot 으로 보낸다 — 응원 구단 조회는 1회")
    void subscribeSupportTeam_registersTeamKey_andSendsTeamSnapshot() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        SseEmitter emitter = new SseEmitter();
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(1L)).willReturn(Optional.of(supportTeam));
        given(supportTeam.getTeam()).willReturn(team);
        given(team.getId()).willReturn(3L);
        given(gameRepository.findAllByTeamAndGameDateRange(eq(3L), eq(date.atStartOfDay()),
                eq(date.plusDays(1).atStartOfDay()))).willReturn(List.of());
        given(registry.register(GameSseRegistry.teamKey(date, 3L))).willReturn(emitter);

        SseEmitter result = service.subscribeSupportTeam(1L, date);

        assertThat(result).isSameAs(emitter);
        verify(registry).sendSnapshot(emitter, List.of());
        verifyNoInteractions(gameService);
    }

    @Test
    @DisplayName("활성 응원 구단이 없으면 빈 snapshot 만 보내고 스트림을 닫는다 — 레지스트리에 올리지 않는다")
    void subscribeSupportTeam_noSupportTeam_sendsEmptySnapshotAndCompletes() {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(1L)).willReturn(Optional.empty());

        SseEmitter result = service.subscribeSupportTeam(1L, LocalDate.of(2026, 8, 1));

        assertThat(result).isNotNull();
        verify(registry).sendSnapshot(eq(result), eq(List.of()));
        verify(registry, never()).register(any());
        verifyNoInteractions(gameRepository, gameService);
    }
}
