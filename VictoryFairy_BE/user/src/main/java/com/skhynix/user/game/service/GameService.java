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
 * 경기 조회 전용 서비스. 경기 데이터는 py-collector 가 소유하므로 쓰기 경로는 두지 않는다
 * ({@code PlayerService}/{@code TeamService} 와 같은 이유로 클래스 레벨 {@code readOnly = true}).
 *
 * <p>트랜잭션이 필수인 이유: prod 는 {@code open-in-view: false} 라 트랜잭션 밖에서 {@code GameResponse.from}
 * 이 LAZY 연관({@code homeTeam}/{@code awayTeam}/{@code gameStatus})을 건드리면 즉시
 * {@code LazyInitializationException} 이 난다(dev 는 {@code open-in-view} 기본값이 true 라 드러나지 않는다).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GameService {

    private final GameRepository gameRepository;

    /**
     * {@code Asia/Seoul} 로 고정된 시계({@code ClockConfig}). "오늘"의 유일한 출처다.
     * 필드로 받는 이유는 테스트에서 {@code Clock.fixed(...)} 로 오늘을 고정하기 위해서다.
     */
    private final Clock clock;

    /**
     * 지정한 날짜의 경기를 {@code gameDate} 오름차순으로 반환한다. 경기가 없으면 빈 리스트다.
     *
     * <p><b>{@code date} 가 {@code null} 이면 "오늘"로 대체한다</b>(파라미터 생략 = 오늘 경기 조회).
     * 기본값 해석을 컨트롤러가 아니라 서비스에서 하는 이유는, "오늘"이 시간대 규칙이 걸린 도메인 판단이라
     * 시계를 아는 쪽에 두어야 하기 때문이다. 다만 <b>형식이 잘못된 값은 여기까지 오지 않고</b> 컨트롤러
     * 진입 전 타입 변환에서 400 이 난다 — "없으면 오늘"이지 "이상하면 오늘"이 아니다.
     *
     * <p><b>오늘은 반드시 {@code Asia/Seoul} 기준이다.</b> 운영 파드가 UTC 로 돌기 때문에
     * {@code LocalDate.now()}(시스템 기본 시간대)를 쓰면 KST 자정~오전 9시 사이에 하루 전 날짜로 조회된다.
     * KBO 경기 일정은 한국 날짜 기준이라 그 시간대의 사용자에게 전날 경기가 보이는 오답이 된다.
     * 시간대 고정 근거의 상세는 {@code ClockConfig} 참고.
     *
     * <p>{@code games.game_date} 가 {@code datetime(6)} 이라 날짜 등치 비교로는 한 건도 잡히지 않는다.
     * 하루를 반개구간 {@code [date 00:00, date+1일 00:00)} 으로 바꿔 넘긴다 — 상한을 포함시키지 않으므로
     * 자정 경기가 이틀에 중복되거나 마이크로초 단위 값이 누락되는 경계 문제가 없다.
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
