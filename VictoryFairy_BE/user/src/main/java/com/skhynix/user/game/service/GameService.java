package com.skhynix.user.game.service;

import com.skhynix.domain.game.repository.GameRepository;
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
}
