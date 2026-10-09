package com.skhynix.user.game.service;

import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.user.game.dto.GameResponse;
import com.skhynix.user.game.realtime.GameSseRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 경기 SSE 구독({@code GET /games/subscribe}·{@code GET /games/support/subscribe}).
 *
 * <p>연결 직후 {@code snapshot}(현재 목록, {@code GET /games}와 같은 13필드)을 보내고 레지스트리에 등록한다.
 * 이후 갱신은 {@code GameStateEventListener} → {@code GameEventPublisher} → {@code GameSseRegistry}가
 * {@code game-update}로 밀어 넣는다. 날짜 해석(생략 시 {@code Asia/Seoul} 오늘, 반개구간)은
 * {@link GameService}와 같은 규칙이다.
 *
 * <p>클래스 레벨 트랜잭션은 {@link GameService}와 같은 이유로 필수다(prod {@code open-in-view: false}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameSubscriptionService {

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final UserSupportTeamRepository userSupportTeamRepository;
    private final GameSseRegistry registry;
    private final Clock clock;

    public SseEmitter subscribe(LocalDate date) {
        LocalDate target = resolve(date);
        List<GameResponse> snapshot = gameService.getGames(target);
        SseEmitter emitter = registry.register(GameSseRegistry.dateKey(target));
        registry.sendSnapshot(emitter, snapshot);
        return emitter;
    }

    /**
     * 활성 응원 구단이 없으면 {@code GET /games/support}가 200 + 빈 배열을 주는 것과 같은 뜻으로, 빈
     * {@code snapshot}만 보내고 스트림을 닫는다 — 레지스트리에는 올리지 않는다(올려 둘 구단 키가 없다).
     * 응원 구단 조회는 요청당 1회다 — {@link GameService#getSupportTeamGames}를 거치면 한 번 더 읽는다.
     */
    public SseEmitter subscribeSupportTeam(Long userAccountId, LocalDate date) {
        LocalDate target = resolve(date);
        return userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> {
                    Long teamId = support.getTeam().getId();
                    List<GameResponse> snapshot = gameRepository.findAllByTeamAndGameDateRange(teamId,
                                    target.atStartOfDay(), target.plusDays(1).atStartOfDay())
                            .stream().map(GameResponse::from).toList();
                    SseEmitter emitter = registry.register(GameSseRegistry.teamKey(target, teamId));
                    registry.sendSnapshot(emitter, snapshot);
                    return emitter;
                })
                .orElseGet(() -> {
                    SseEmitter emitter = new SseEmitter(0L);
                    registry.sendSnapshot(emitter, List.of());
                    emitter.complete();
                    return emitter;
                });
    }

    private LocalDate resolve(LocalDate date) {
        return (date != null) ? date : LocalDate.now(clock);
    }
}
