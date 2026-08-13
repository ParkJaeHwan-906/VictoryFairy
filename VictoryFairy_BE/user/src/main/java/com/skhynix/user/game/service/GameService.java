package com.skhynix.user.game.service;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.user.game.dto.GameResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// prod 는 open-in-view: false 라 트랜잭션 밖에서 GameResponse.from 이 LAZY 연관을 건드리면
// LazyInitializationException 이 난다(dev 는 기본값 true 라 드러나지 않는다) — 클래스 레벨 트랜잭션이 필수다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameService {

    private final GameRepository gameRepository;

    private final UserSupportTeamRepository userSupportTeamRepository;

    // 테스트에서 Clock.fixed(...)로 "오늘"을 고정할 수 있도록 값이 아니라 시계를 주입받는다(ClockConfig 참고).
    private final Clock clock;

    // games.game_date 가 datetime(6) 이라 날짜 등치 비교로는 매치가 안 된다 — 반개구간으로 넘겨
    // 자정 경기 중복·마이크로초 누락 경계 문제를 피한다.
    public List<GameResponse> getGames(LocalDate date) {
        LocalDate target = (date != null) ? date : LocalDate.now(clock);
        return gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                        target.atStartOfDay(), target.plusDays(1).atStartOfDay())
                .stream()
                .map(GameResponse::from)
                .toList();
    }

    // 구단명이 응답에 안 실려 @EntityGraph 변형(findWithTeamBy...)을 쓰지 않는다 — 프록시의 id 접근은
    // 초기화를 유발하지 않아 조인 없이도 SELECT 가 늘지 않는다.
    public List<GameResponse> getSupportTeamGames(Long userAccountId, LocalDate date) {
        LocalDate target = (date != null) ? date : LocalDate.now(clock);
        List<Game> games = userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(userAccountId)
                .map(support -> gameRepository.findAllByTeamAndGameDateRange(support.getTeam().getId(),
                        target.atStartOfDay(), target.plusDays(1).atStartOfDay()))
                .orElseGet(List::of);
        return games.stream()
                .map(GameResponse::from)
                .toList();
    }
}
