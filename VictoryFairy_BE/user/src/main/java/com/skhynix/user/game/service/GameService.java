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

/**
 * 경기 조회 전용 서비스. 경기 데이터는 py-collector 가 소유하므로 쓰기 경로는 두지 않는다.
 *
 * <p>prod 는 {@code open-in-view: false} 라 트랜잭션 밖에서 {@code GameResponse.from} 이 LAZY 연관
 * ({@code homeTeam}/{@code awayTeam}/{@code gameStatus})을 건드리면 즉시
 * {@code LazyInitializationException} 이 난다(dev 는 {@code open-in-view} 기본값이 true 라 드러나지
 * 않는다) — 그래서 클래스 레벨 트랜잭션이 필수다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameService {

    private final GameRepository gameRepository;

    private final UserSupportTeamRepository userSupportTeamRepository;

    // 테스트에서 Clock.fixed(...)로 "오늘"을 고정할 수 있도록 값이 아니라 시계를 주입받는다(ClockConfig 참고).
    private final Clock clock;

    /**
     * 지정한 날짜의 경기를 {@code gameDate} 오름차순으로 반환한다. {@code date} 가 {@code null} 이면
     * {@code Asia/Seoul} 기준 오늘로 대체한다(형식이 잘못된 값은 컨트롤러 진입 전 타입 변환에서 400 —
     * 여기까지 오지 않는다).
     *
     * <p>{@code games.game_date} 가 {@code datetime(6)} 이라 날짜 등치 비교로는 매치가 안 된다. 하루를
     * 반개구간 {@code [date 00:00, date+1일 00:00)} 으로 바꿔 넘겨 자정 경기 중복·마이크로초 누락 경계
     * 문제를 피한다.
     *
     * @param date 조회할 날짜. {@code null} 이면 한국 기준 오늘
     */
    public List<GameResponse> getGames(LocalDate date) {
        LocalDate target = (date != null) ? date : LocalDate.now(clock);
        return gameRepository
                .findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                        target.atStartOfDay(), target.plusDays(1).atStartOfDay())
                .stream()
                .map(GameResponse::from)
                .toList();
    }

    /**
     * 요청 계정의 <b>활성 응원 구단</b>({@code oppose is null})이 홈 또는 원정으로 나서는 경기만
     * {@code gameDate} 오름차순으로 반환한다. 날짜 해석 규칙은 {@link #getGames(LocalDate)} 와 같다.
     *
     * <p>활성 응원 구단이 없으면(한 번도 고르지 않았거나 전부 취소됐으면) 경기 조회를 아예 내지 않고 빈
     * 리스트다 — 오류가 아니다. 그래서 "경기 없는 날"과 응답이 구분되지 않는데, 이는 백엔드가 응원 구단
     * 선택을 강제하지 않는다는 기존 정책을 따른 의도된 결과다.
     *
     * <p>구단명은 응답에 실리지 않으므로 응원 구단 조회에 {@code @EntityGraph} 변형
     * ({@code findWithTeamByUserAccount_IdAndOpposeIsNull})을 쓰지 않는다 — 프록시의 id 접근은 초기화를
     * 유발하지 않아 조인 없이도 SELECT 가 늘지 않는다({@code PlayerService.resolveTeamId} 와 같은 판단).
     *
     * @param userAccountId 인증된 요청의 principal(이 경로는 인증 필수라 {@code null} 이 아니다)
     * @param date 조회할 날짜. {@code null} 이면 한국 기준 오늘
     */
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
