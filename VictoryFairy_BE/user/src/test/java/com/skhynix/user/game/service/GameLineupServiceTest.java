package com.skhynix.user.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.game.entity.Game;
import com.skhynix.domain.game.entity.GameLineup;
import com.skhynix.domain.game.entity.GameStatus;
import com.skhynix.domain.game.entity.Position;
import com.skhynix.domain.game.repository.GameLineupRepository;
import com.skhynix.domain.game.repository.GameRepository;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.user.game.dto.GameLineupResponse;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code GameLineupService.getLineup(String)} 단위 테스트. 계약 출처는
 * {@code docs/requirements/user/game-lineup.md}(USER-GL-1~29)이되, 2026-08-04 승인 이후 지시로
 * 아래 2건은 문서보다 우선한다.
 * <ul>
 *   <li>빈/공백 {@code gameId}(예: {@code ?gameId=})는 400이 아니라 <b>404 GAME_NOT_FOUND</b>다
 *       (USER-GL-22 확정치와 일치, 문서 개정 이력 참고).</li>
 *   <li>타자 판정은 {@code bat_order IS NOT NULL}이 아니라 <b>{@code bat_order BETWEEN 1 AND 9}</b>다
 *       (USER-GL-5 확정치, {@code GameLineupRepository.findStarterBatters} 쿼리와 일치).</li>
 * </ul>
 *
 * <p>협력자는 {@link GameRepository}·{@link GameLineupRepository} 2개이며, 실제 리포지토리 쿼리
 * 조건(is_starter/bat_order 범위/left join 등)은 이 테스트의 검증 대상이 아니다(리포지토리 테스트 몫,
 * 이 모듈은 스텁으로 리포지토리 결과를 미리 정해준다). 이 테스트가 고정하는 것은 오직
 * <b>서비스가 리포지토리 결과를 어떻게 조립하는가</b>다: naverGameId → 내부 id 해석, 404 판정,
 * teamId 1차 그룹핑과 그룹 내부 순서 보존, 빈 그룹 미생성.
 */
@ExtendWith(MockitoExtension.class)
class GameLineupServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameLineupRepository gameLineupRepository;

    private GameLineupService gameLineupService;

    @BeforeEach
    void setUp() {
        gameLineupService = new GameLineupService(gameRepository, gameLineupRepository);
    }

    private Team teamOf(Long id, String name) {
        Team team = Team.builder().name(name).code(name).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private Game gameOf(Long id, String naverGameId) {
        Game game = Game.builder()
                .gameDate(java.time.LocalDateTime.of(2026, 8, 1, 18, 30))
                .homeTeam(teamOf(100L, "홈더미"))
                .awayTeam(teamOf(200L, "원정더미"))
                .gameStatus(GameStatus.builder().name("SCHEDULED").build())
                .naverGameId(naverGameId)
                .build();
        ReflectionTestUtils.setField(game, "id", id);
        return game;
    }

    private Player playerOf(Team team, String name) {
        return Player.builder().team(team).name(name).average(0).build();
    }

    private Position positionOf(String name) {
        return Position.builder().name(name).build();
    }

    private GameLineup pitcherOf(Game game, Team team, String name, Position position) {
        return GameLineup.builder()
                .game(game)
                .team(team)
                .player(playerOf(team, name))
                .batOrder(null)
                .position(position)
                .isStarter(true)
                .build();
    }

    private GameLineup batterOf(Game game, Team team, String name, int batOrder, Position position) {
        return GameLineup.builder()
                .game(game)
                .team(team)
                .player(playerOf(team, name))
                .batOrder(batOrder)
                .position(position)
                .isStarter(true)
                .build();
    }

    @Test
    @DisplayName("[USER-GL-2] gameId를 naverGameId로 해석해 조회하고, 그 결과의 내부 id로 "
            + "선발투수/타자 리포지토리를 호출한다(내부 PK가 아니라 자연키로 검색)")
    void getLineup_resolvesNaverGameIdToInternalIdBeforeQueryingLineups() {
        // given
        Game game = gameOf(42L, "20260801LGSS02026");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        given(gameLineupRepository.findStarterPitchers(42L)).willReturn(Collections.emptyList());
        given(gameLineupRepository.findStarterBatters(42L)).willReturn(Collections.emptyList());

        // when
        gameLineupService.getLineup("20260801LGSS02026");

        // then
        verify(gameRepository).findByNaverGameId("20260801LGSS02026");
        verify(gameLineupRepository).findStarterPitchers(eq(42L));
        verify(gameLineupRepository).findStarterBatters(eq(42L));
    }

    @Test
    @DisplayName("[USER-GL-20] 일치하는 naverGameId 행이 없으면 BusinessException(GAME_NOT_FOUND)을 던진다")
    void getLineup_noMatchingGame_throwsGameNotFound() {
        // given
        given(gameRepository.findByNaverGameId("없는값")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> gameLineupService.getLineup("없는값"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GAME_NOT_FOUND);
    }

    @Test
    @DisplayName("[USER-GL-22, 승인 시점 지시 우선] 빈 문자열 gameId도 별도 분기 없이 같은 404 경로로 "
            + "흡수된다(naverGameId 조회가 빈 Optional을 반환하는 것과 동일하게 처리)")
    void getLineup_blankGameId_throwsGameNotFoundViaSamePath() {
        // given
        given(gameRepository.findByNaverGameId("")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> gameLineupService.getLineup(""))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GAME_NOT_FOUND);
        verify(gameRepository).findByNaverGameId("");
    }

    @Test
    @DisplayName("[USER-GL-21] 경기는 있으나 선발 라인업이 0건이면 예외 없이 빈 리스트를 반환한다")
    void getLineup_gameExistsButNoLineupRows_returnsEmptyListWithoutException() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(Collections.emptyList());
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(Collections.emptyList());

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[USER-GL-14/27] 응답은 teamId로 1차 그룹핑되고 그룹은 teamId 오름차순이다")
    void getLineup_groupsByTeamId_sortedAscending() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        Team teamHigh = teamOf(9L, "높은팀");
        Team teamLow = teamOf(3L, "낮은팀");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        // 리포지토리가 낮은/높은 팀 순서를 보장하지 않는다고 가정 — 높은 팀을 먼저 준다.
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(List.of(
                pitcherOf(game, teamHigh, "투수높은", positionOf("P")),
                pitcherOf(game, teamLow, "투수낮은", positionOf("P"))));
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(List.of(
                batterOf(game, teamHigh, "타자높은", 1, positionOf("CF")),
                batterOf(game, teamLow, "타자낮은", 1, positionOf("SS"))));

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).extracting(GameLineupResponse::teamId).containsExactly(3L, 9L);
    }

    @Test
    @DisplayName("[핵심 회귀] 그룹핑이 리포지토리가 준 순서(타자 batOrder 오름차순/투수 이름 오름차순)를 "
            + "흐트러뜨리지 않는다")
    void getLineup_groupingPreservesRepositoryOrderWithinGroup() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        Team team = teamOf(3L, "삼성");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        // 투수 이름 오름차순으로 이미 정렬돼 온다고 가정(리포지토리 계약)
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(List.of(
                pitcherOf(game, team, "가나다", positionOf("P")),
                pitcherOf(game, team, "마바사", positionOf("P"))));
        // 타순 오름차순으로 이미 정렬돼 온다고 가정(리포지토리 계약)
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(List.of(
                batterOf(game, team, "일번타자", 1, positionOf("CF")),
                batterOf(game, team, "이번타자", 2, positionOf("2B")),
                batterOf(game, team, "삼번타자", 3, positionOf("SS"))));

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).hasSize(1);
        GameLineupResponse group = result.get(0);
        assertThat(group.pitchers()).extracting(GameLineupResponse.Pitcher::name)
                .containsExactly("가나다", "마바사");
        assertThat(group.batters()).extracting(GameLineupResponse.Batter::name)
                .containsExactly("일번타자", "이번타자", "삼번타자");
        assertThat(group.batters()).extracting(GameLineupResponse.Batter::batOrder)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[USER-GL-26/28] 한 팀 라인업만 적재된 경우 그룹은 1개이고 빈 그룹을 만들지 않는다")
    void getLineup_onlyOneTeamLoaded_returnsSingleGroupWithoutEmptyOpponentGroup() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        Team loadedTeam = teamOf(3L, "삼성");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(List.of(
                pitcherOf(game, loadedTeam, "원태인", positionOf("P"))));
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(List.of(
                batterOf(game, loadedTeam, "김지찬", 1, positionOf("CF"))));

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).teamId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("투수만 있고 타자가 없는 팀, 반대로 타자만 있는 팀도 그룹은 만들어지되 해당 목록만 "
            + "빈 리스트다")
    void getLineup_teamsWithOnlyPitchersOrOnlyBatters_stillFormGroupsWithEmptyOtherList() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        Team pitcherOnlyTeam = teamOf(3L, "투수만있는팀");
        Team batterOnlyTeam = teamOf(9L, "타자만있는팀");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(List.of(
                pitcherOf(game, pitcherOnlyTeam, "투수만", positionOf("P"))));
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(List.of(
                batterOf(game, batterOnlyTeam, "타자만", 1, positionOf("CF"))));

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).hasSize(2);
        GameLineupResponse pitcherOnlyGroup = result.stream()
                .filter(g -> g.teamId().equals(3L)).findFirst().orElseThrow();
        assertThat(pitcherOnlyGroup.pitchers()).hasSize(1);
        assertThat(pitcherOnlyGroup.batters()).isEmpty();

        GameLineupResponse batterOnlyGroup = result.stream()
                .filter(g -> g.teamId().equals(9L)).findFirst().orElseThrow();
        assertThat(batterOnlyGroup.batters()).hasSize(1);
        assertThat(batterOnlyGroup.pitchers()).isEmpty();
    }

    @Test
    @DisplayName("[USER-GL-23] position이 null인 행은 positionName이 null이다(대체 문자열이 아니다)")
    void getLineup_nullPosition_mapsToNullPositionNameNotSubstituteString() {
        // given
        Game game = gameOf(1L, "20260801LGSS02026");
        Team team = teamOf(3L, "삼성");
        given(gameRepository.findByNaverGameId("20260801LGSS02026")).willReturn(Optional.of(game));
        given(gameLineupRepository.findStarterPitchers(1L)).willReturn(List.of(
                pitcherOf(game, team, "포지션미상투수", null)));
        given(gameLineupRepository.findStarterBatters(1L)).willReturn(List.of(
                batterOf(game, team, "포지션미상타자", 1, null)));

        // when
        List<GameLineupResponse> result = gameLineupService.getLineup("20260801LGSS02026");

        // then
        assertThat(result).hasSize(1);
        GameLineupResponse group = result.get(0);
        assertThat(group.pitchers().get(0).positionName()).isNull();
        assertThat(group.batters().get(0).positionName()).isNull();
    }
}
