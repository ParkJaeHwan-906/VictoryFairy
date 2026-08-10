package com.skhynix.user.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.stadium.entity.Stadium;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.user.game.dto.GameResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code GameService.getGames(LocalDate)} 단위 테스트.
 *
 * <p>이번 수정의 핵심은 {@code games.game_date}가 {@code datetime(6)}이라 날짜 등치 비교로는
 * 항상 0건이었던 기존 버그를 반개구간 {@code [date.atStartOfDay(), date.plusDays(1).atStartOfDay())}
 * 로 바꾼 것이다 — {@link #getGames_passesHalfOpenIntervalBoundariesToRepository()}가 리포지토리에
 * 넘어가는 두 경계값을 {@link ArgumentCaptor}로 정확히 검증한다.
 *
 * <p>{@code @InjectMocks} 대신 {@code @BeforeEach}에서 {@link Clock}을 명시적으로 생성자 주입한다.
 * {@code @InjectMocks}를 쓰면 {@code Clock}에 조용히 {@code null}이 들어가는데, 기존 테스트가 전부
 * non-null {@code date}만 넘겨 {@code LocalDate.now(clock)}(clock이 null이면 NPE)이 호출되지 않는
 * 경로만 타서 우연히 통과했었다. {@code date == null} 케이스를 검증하려면 실제로 유효한 {@link Clock}이
 * 필요하다.
 */
@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    /** date를 명시적으로 넘기는 기존 테스트들이 쓰는 기본 clock. 값 자체는 그 테스트들에서 쓰이지 않는다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private GameRepository gameRepository;

    private GameService gameService;

    @BeforeEach
    void setUp() {
        gameService = new GameService(gameRepository, FIXED_CLOCK);
    }

    private Team teamOf(String name) {
        return Team.builder().name(name).code(name).build();
    }

    // GameResponse.homeTeamId()/awayTeamId() 단언에는 실제 PK 값이 필요하다. Team.id는
    // @GeneratedValue라 빌더에 없어 TeamServiceTest와 같은 패턴(ReflectionTestUtils)으로 채운다.
    private Team teamOf(Long id, String name) {
        Team team = teamOf(name);
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private GameStatus statusOf(String name) {
        return GameStatus.builder().name(name).build();
    }

    private Stadium stadiumOf(String name) {
        return Stadium.builder().name(name).build();
    }

    // 이 helper 는 stadium이 있는(non-null) 정상 케이스를 표현한다. stadium이 null인 케이스는
    // getGames_gameWithNullStadium_mapsToResponseWithNullStadium()에서 Game.builder()를 직접 써서
    // 별도로 다룬다(GameResponse.stadium()이 null-safe하게 매핑되는지 검증).
    private Game gameOf(LocalDateTime gameDate, String homeTeamName, String awayTeamName,
            Integer homeScore, Integer awayScore, String statusName, String naverGameId) {
        return Game.builder()
                .gameDate(gameDate)
                .homeTeam(teamOf(homeTeamName))
                .awayTeam(teamOf(awayTeamName))
                .stadium(stadiumOf("잠실야구장"))
                .homeScore(homeScore)
                .awayScore(awayScore)
                .gameStatus(statusOf(statusName))
                .naverGameId(naverGameId)
                .build();
    }

    @Test
    @DisplayName("리포지토리에는 date.atStartOfDay()와 date.plusDays(1).atStartOfDay()를 반개구간 "
            + "경계값 그대로 넘긴다(날짜 등치 비교가 아니다)")
    void getGames_passesHalfOpenIntervalBoundariesToRepository() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 1);
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                any(), any())).willReturn(Collections.emptyList());

        // when
        gameService.getGames(date);

        // then
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gameRepository).findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(date.atStartOfDay());
        assertThat(endCaptor.getValue()).isEqualTo(date.plusDays(1).atStartOfDay());
        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 2, 0, 0, 0));
    }

    @Test
    @DisplayName("연월경계(말일)에도 다음날 자정을 상한으로 정확히 넘긴다(달을 넘어가는 경계값)")
    void getGames_monthBoundaryDate_passesCorrectNextDayStart() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 31);
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                any(), any())).willReturn(Collections.emptyList());

        // when
        gameService.getGames(date);

        // then
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gameRepository).findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 31, 0, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("경기가 없으면 예외 없이 빈 리스트를 반환한다")
    void getGames_noRows_returnsEmptyListWithoutException() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 1);
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay())).willReturn(Collections.emptyList());

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("리포지토리가 준 경기 엔티티를 GameResponse의 8개 필드로 정확히 변환한다")
    void getGames_mapsAllFieldsFromEntityToResponse() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime gameDateTime = LocalDateTime.of(2026, 8, 1, 18, 30, 0);
        Game game = gameOf(gameDateTime, "LG", "KIA", 3, 5, "FINISHED", "20260801LGHT02026");
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay())).willReturn(List.of(game));

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        assertThat(result).hasSize(1);
        GameResponse response = result.get(0);
        assertThat(response.gameId()).isEqualTo("20260801LGHT02026");
        assertThat(response.stadium()).isEqualTo("잠실야구장");
        assertThat(response.homeTeam()).isEqualTo("LG");
        assertThat(response.awayTeam()).isEqualTo("KIA");
        assertThat(response.homeTeamScore()).isEqualTo(3);
        assertThat(response.awayTeamScore()).isEqualTo(5);
        assertThat(response.gameDate()).isEqualTo(gameDateTime);
        assertThat(response.gameState()).isEqualTo("FINISHED");
        assertThat(response.cancelReason()).isNull();
    }

    @Test
    @DisplayName("취소된 경기는 cancelReason이 응답까지 그대로 전달된다(취소가 아니면 null)")
    void getGames_canceledGame_mapsCancelReasonToResponse() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 9);
        LocalDateTime gameDateTime = LocalDateTime.of(2026, 8, 9, 18, 0, 0);
        Game canceled = Game.builder()
                .gameDate(gameDateTime)
                .homeTeam(teamOf("LG"))
                .awayTeam(teamOf("KIA"))
                .stadium(stadiumOf("잠실야구장"))
                .gameStatus(statusOf("CANCELED"))
                .cancelReason("폭염취소")
                .naverGameId("20260809HTLG02026")
                .build();
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay())).willReturn(List.of(canceled));

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        GameResponse response = result.get(0);
        assertThat(response.gameState()).isEqualTo("CANCELED");
        assertThat(response.cancelReason()).isEqualTo("폭염취소");
        // 취소 경기의 0-0 은 껍데기라 점수는 적재되지 않는다(py-collector 규약)
        assertThat(response.homeTeamScore()).isNull();
        assertThat(response.awayTeamScore()).isNull();
    }

    @Test
    @DisplayName("[USER-GTID-1/2/5] homeTeamId/awayTeamId가 실제 구단 PK로 채워지고 절대 null이 아니다"
            + "(homeTeam/awayTeam은 optional=false)")
    void getGames_mapsHomeAwayTeamIds_toActualTeamPksNeverNull() {
        // given
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime gameDateTime = LocalDateTime.of(2026, 8, 1, 18, 30, 0);
        Team home = teamOf(3L, "LG");
        Team away = teamOf(7L, "KIA");
        Game game = Game.builder()
                .gameDate(gameDateTime)
                .homeTeam(home)
                .awayTeam(away)
                .stadium(stadiumOf("잠실야구장"))
                .homeScore(3)
                .awayScore(5)
                .gameStatus(statusOf("FINISHED"))
                .naverGameId("20260801LGHT02026")
                .build();
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay())).willReturn(List.of(game));

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        assertThat(result).hasSize(1);
        GameResponse response = result.get(0);
        assertThat(response.homeTeamId()).isNotNull().isEqualTo(3L);
        assertThat(response.awayTeamId()).isNotNull().isEqualTo(7L);
        assertThat(response.homeTeam()).isEqualTo("LG");
        assertThat(response.awayTeam()).isEqualTo("KIA");
    }

    @Test
    @DisplayName("stadium이 null인 경기(FK가 nullable, 구장 미정)를 조회해도 예외 없이 "
            + "stadium이 null인 GameResponse로 매핑된다(나머지 7개 필드는 정상 매핑)")
    void getGames_gameWithNullStadium_mapsToResponseWithNullStadium() {
        // given: stadium을 세팅하지 않은 경기(= FK null, 실제 스키마에서 허용되는 상태)
        LocalDate date = LocalDate.of(2026, 8, 1);
        LocalDateTime gameDateTime = LocalDateTime.of(2026, 8, 1, 18, 30, 0);
        Game gameWithoutStadium = Game.builder()
                .gameDate(gameDateTime)
                .homeTeam(teamOf("LG"))
                .awayTeam(teamOf("KIA"))
                .homeScore(3)
                .awayScore(5)
                .gameStatus(statusOf("FINISHED"))
                .naverGameId("20260801LGHT02026")
                .build();
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .willReturn(List.of(gameWithoutStadium));

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        assertThat(result).hasSize(1);
        GameResponse response = result.get(0);
        assertThat(response.gameId()).isEqualTo("20260801LGHT02026");
        assertThat(response.stadium()).isNull();
        assertThat(response.homeTeam()).isEqualTo("LG");
        assertThat(response.awayTeam()).isEqualTo("KIA");
        assertThat(response.homeTeamScore()).isEqualTo(3);
        assertThat(response.awayTeamScore()).isEqualTo(5);
        assertThat(response.gameDate()).isEqualTo(gameDateTime);
        assertThat(response.gameState()).isEqualTo("FINISHED");
    }

    @Test
    @DisplayName("리포지토리가 준 gameDate 오름차순을 서비스가 재정렬하지 않고 그대로 흘려보낸다")
    void getGames_doesNotReorderRepositoryResult() {
        // given: 리포지토리가 이미 gameDate 오름차순으로 정렬해 준다는 계약을 스텁으로 표현
        LocalDate date = LocalDate.of(2026, 8, 1);
        Game earlyGame = gameOf(LocalDateTime.of(2026, 8, 1, 14, 0), "두산", "SSG", 1, 0, "FINISHED",
                "20260801OBSK02026");
        Game lateGame = gameOf(LocalDateTime.of(2026, 8, 1, 18, 30), "LG", "KIA", 3, 5, "IN_PROGRESS",
                "20260801LGHT02026");
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                .willReturn(List.of(earlyGame, lateGame));

        // when
        List<GameResponse> result = gameService.getGames(date);

        // then
        assertThat(result).extracting(GameResponse::gameId)
                .containsExactly("20260801OBSK02026", "20260801LGHT02026");
    }

    @Test
    @DisplayName("date가 null이면 고정된 clock 기준 오늘의 atStartOfDay()와 다음날 atStartOfDay()를 "
            + "리포지토리에 넘긴다")
    void getGames_dateOmitted_usesTodayFromClock() {
        // given: KST 2026-08-01T09:00:00 (= UTC 2026-08-01T00:00:00)로 clock 고정
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        GameService service = new GameService(gameRepository, fixedClock);
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                any(), any())).willReturn(Collections.emptyList());

        // when
        service.getGames(null);

        // then
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gameRepository).findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 2, 0, 0, 0));
    }

    @Test
    @DisplayName("[회귀] UTC로는 8/1이지만 KST로는 8/2로 넘어간 순간(2026-08-01T15:30:00Z = "
            + "KST 2026-08-02T00:30:00)에 date를 생략하면 조회 대상은 8/1이 아니라 8/2다 — "
            + "LocalDate.now(clock) 대신 시스템 기본 시간대의 LocalDate.now()로 되돌리면 이 테스트가 깨진다")
    void getGames_dateOmitted_utcKstDateBoundary_targetsKstDateNotUtcDate() {
        // given: UTC 자정을 넘겨 KST 날짜가 하루 앞서가는 순간으로 clock 고정
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-01T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        GameService service = new GameService(gameRepository, fixedClock);
        given(gameRepository.findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                any(), any())).willReturn(Collections.emptyList());

        // when
        service.getGames(null);

        // then: KST 기준 오늘은 8/2다(UTC 기준이면 8/1로 잘못 조회된다)
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gameRepository).findAllByGameDateGreaterThanEqualAndGameDateLessThanOrderByGameDateAsc(
                startCaptor.capture(), endCaptor.capture());

        assertThat(startCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 2, 0, 0, 0));
        assertThat(endCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0, 0));
    }
}
