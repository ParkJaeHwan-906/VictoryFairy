package com.skhynix.user.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.player.entity.Player;
import com.skhynix.domain.player.entity.PositionGroup;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.entity.UserSupportPlayer;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.player.dto.PlayerResponse;
import com.skhynix.user.team.dto.TeamResponse;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code SupportService} 단위 테스트.
 * 요구사항: {@code docs/requirements/user/support-selection.md}(USER-SP-1 ~ 29).
 *
 * <p>여기서 지키는지 확인하는 것은 문서가 정의한 네 불변식이다 — 구단 1개(SP-12), 선수는 응원 구단
 * 소속(SP-17), 구단 변경 시 선수 전원 취소(SP-10), 취소는 삭제가 아닌 {@code oppose} 전이(SP-24).
 *
 * <p><b>재응원이 새 행이 아니라 기존 행 재활성인지</b>(SP-9/19)를 {@code never()).save(any())} 로 못박는다 —
 * 실 DB 에서는 UNIQUE 위반 500 으로 드러날 버그라 목 기반으로라도 호출 자체를 금지해 두는 편이 낫다.
 *
 * <p>엔티티는 {@code id} 에 setter 가 없어({@code @GeneratedValue}) 고정 id 로 단언하려면
 * {@link ReflectionTestUtils#setField} 로 채운다(다른 테스트와 같은 패턴).
 *
 * <p><b>한계</b>: 리포지토리를 목으로 대체하므로 UNIQUE 제약·FK CASCADE·트랜잭션 롤백은 실제로 검증되지
 * 않는다. 저장소에 H2/Testcontainers 가 없어 {@code @DataJpaTest} 라운드트립이 불가한 상태이며, 문서의
 * "미커버 영역"에 같은 내용을 기록했다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportServiceTest {

    private static final Long ACCOUNT_ID = 1L;

    @Mock
    private UserSupportTeamRepository userSupportTeamRepository;

    @Mock
    private UserSupportPlayerRepository userSupportPlayerRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private SupportService supportService;

    private static final Team KIA = teamOf(6L, "KIA", "HT");
    private static final Team LG = teamOf(8L, "LG", "LG");

    private static Team teamOf(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static Player playerOf(Long id, String name, Team team) {
        Player player = Player.builder()
                .team(team)
                .name(name)
                .average(0.3)
                .uniformNumber("1" + id)
                .positionGroup(PositionGroup.INFIELDER)
                .build();
        ReflectionTestUtils.setField(player, "id", id);
        return player;
    }

    /** {@link #playerOf} 픽스처와 짝을 이루는 기대 DTO — 응원 API는 선수 목록 API와 같은 계약을 돌려준다. */
    private static PlayerResponse responseOf(Long id, String name, Team team) {
        return new PlayerResponse(team.getId(), team.getName(), id, name,
                "1" + id, PositionGroup.INFIELDER.name());
    }

    private static UserAccount account() {
        return UserAccount.builder().nickname("응원러").password("encoded").build();
    }

    private static UserSupportTeam supportTeamOf(Team team) {
        return UserSupportTeam.builder().userAccount(account()).team(team).build();
    }

    private static UserSupportPlayer supportPlayerOf(Player player) {
        return UserSupportPlayer.builder().userAccount(account()).player(player).build();
    }

    // ---------- 응원 구단 선택 ----------

    @Test
    @DisplayName("[USER-SP-5] 존재하지 않는 구단을 선택하면 TEAM_NOT_FOUND(404)를 던지고 아무 행도 만들지 않는다")
    void selectTeam_unknownTeam_throwsTeamNotFound() {
        // given
        given(teamRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> supportService.selectTeam(ACCOUNT_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_NOT_FOUND);

        verify(userSupportTeamRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-6, USER-SP-13] 응원 이력이 없으면 oppose가 null인 행을 새로 만들고 현재 응원 구단을 반환한다")
    void selectTeam_noHistory_createsActiveRow() {
        // given
        given(teamRepository.findById(6L)).willReturn(Optional.of(KIA));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 6L))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(userSupportTeamRepository.save(any())).willAnswer(call -> call.getArgument(0));

        // when
        TeamResponse result = supportService.selectTeam(ACCOUNT_ID, 6L);

        // then
        assertThat(result).isEqualTo(new TeamResponse(6L, "KIA"));
        verify(userSupportTeamRepository).save(any(UserSupportTeam.class));
    }

    @Test
    @DisplayName("[USER-SP-7] 이미 응원 중인 구단을 다시 선택하면 새 행을 만들지 않고 상태를 바꾸지 않는다")
    void selectTeam_sameTeam_isNoOp() {
        // given
        UserSupportTeam current = supportTeamOf(KIA);
        given(teamRepository.findById(6L)).willReturn(Optional.of(KIA));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(current));

        // when
        TeamResponse result = supportService.selectTeam(ACCOUNT_ID, 6L);

        // then
        assertThat(result).isEqualTo(new TeamResponse(6L, "KIA"));
        assertThat(current.isOpposed()).isFalse();
        verify(userSupportTeamRepository, never()).save(any());
        // oppose 무관 조회까지 타지 않는다 — 조기 반환이 실제로 앞단에서 일어났다는 증거
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndTeam_Id(any(), any());
    }

    @Test
    @DisplayName("[USER-SP-11] 같은 구단 재선택은 응원 선수를 취소하지 않는다")
    void selectTeam_sameTeam_doesNotOpposeSupportedPlayers() {
        // given
        UserSupportPlayer supported = supportPlayerOf(playerOf(2L, "김도영", KIA));
        given(teamRepository.findById(6L)).willReturn(Optional.of(KIA));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));

        // when
        supportService.selectTeam(ACCOUNT_ID, 6L);

        // then
        assertThat(supported.isOpposed()).isFalse();
        verify(userSupportPlayerRepository, never()).findAllByUserAccount_IdAndOpposeIsNull(any());
    }

    @Test
    @DisplayName("[USER-SP-8, USER-SP-12] 다른 구단을 선택하면 기존 행의 oppose를 채우고(삭제 아님) 새 구단만 활성 상태로 남긴다")
    void selectTeam_differentTeam_opposesPreviousAndActivatesTarget() {
        // given
        UserSupportTeam previous = supportTeamOf(KIA);
        given(teamRepository.findById(8L)).willReturn(Optional.of(LG));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(previous));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 8L))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(userSupportTeamRepository.save(any())).willAnswer(call -> call.getArgument(0));

        // when
        TeamResponse result = supportService.selectTeam(ACCOUNT_ID, 8L);

        // then
        assertThat(result).isEqualTo(new TeamResponse(8L, "LG"));
        assertThat(previous.isOpposed()).isTrue(); // 행은 남고 oppose만 채워졌다
        assertThat(previous.getOppose()).isNotNull();
        verify(userSupportTeamRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[USER-SP-9] 과거에 취소했던 구단을 다시 선택하면 새 행을 만들지 않고 기존 행을 재활성한다"
            + "(새 행을 만들면 UNIQUE 위반 500)")
    void selectTeam_previouslyOpposedTeam_reactivatesExistingRow() {
        // given: KIA → LG 로 바꿨다가 다시 KIA 를 고르는 상황
        UserSupportTeam opposedKia = supportTeamOf(KIA);
        opposedKia.oppose(LocalDateTime.of(2026, 7, 1, 0, 0));
        given(teamRepository.findById(6L)).willReturn(Optional.of(KIA));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(LG)));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 6L))
                .willReturn(Optional.of(opposedKia));

        // when
        supportService.selectTeam(ACCOUNT_ID, 6L);

        // then
        assertThat(opposedKia.isOpposed()).isFalse(); // oppose가 null로 되돌아갔다
        verify(userSupportTeamRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-10] 구단이 실제로 바뀌면 응원 중인 선수 전원의 oppose를 구단 행과 같은 시각으로 채운다")
    void selectTeam_changed_opposesAllSupportedPlayersWithSameTimestamp() {
        // given
        UserSupportTeam previous = supportTeamOf(KIA);
        UserSupportPlayer first = supportPlayerOf(playerOf(2L, "김도영", KIA));
        UserSupportPlayer second = supportPlayerOf(playerOf(3L, "양현종", KIA));
        given(teamRepository.findById(8L)).willReturn(Optional.of(LG));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(previous));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(first, second));
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 8L))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(userSupportTeamRepository.save(any())).willAnswer(call -> call.getArgument(0));

        // when
        supportService.selectTeam(ACCOUNT_ID, 8L);

        // then
        assertThat(first.isOpposed()).isTrue();
        assertThat(second.isOpposed()).isTrue();
        // now()를 한 번만 읽는다는 계약: 구단·선수 취소 시각이 정확히 같아야 한다
        assertThat(first.getOppose()).isEqualTo(previous.getOppose());
        assertThat(second.getOppose()).isEqualTo(previous.getOppose());
    }

    // ---------- 응원 선수 추가 ----------

    @Test
    @DisplayName("[USER-SP-15] 응원 구단을 선택하지 않은 상태에서 선수를 추가하면 SUPPORT_TEAM_REQUIRED(400)이며 선수 조회조차 하지 않는다")
    void addPlayers_withoutSupportTeam_throwsSupportTeamRequired() {
        // given
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> supportService.addPlayers(ACCOUNT_ID, List.of(2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SUPPORT_TEAM_REQUIRED);

        // 소속 검사의 기준이 없으므로 선수 검증보다 먼저 판정된다
        verify(playerRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("[USER-SP-16] playerIds에 존재하지 않는 선수가 섞이면 PLAYER_NOT_FOUND(404)이며 같은 요청의 다른 선수도 저장되지 않는다")
    void addPlayers_unknownPlayer_throwsPlayerNotFoundAndSavesNothing() {
        // given: 2건을 요청했지만 조회 결과가 1건 → 하나가 실재하지 않는다
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(2L, 999L)))
                .willReturn(List.of(playerOf(2L, "김도영", KIA)));

        // when & then
        assertThatThrownBy(() -> supportService.addPlayers(ACCOUNT_ID, List.of(2L, 999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAYER_NOT_FOUND);

        verify(userSupportPlayerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-17] 응원 구단 소속이 아닌 선수가 섞이면 PLAYER_NOT_IN_SUPPORT_TEAM(400)이며 같은 요청의 다른 선수도 저장되지 않는다")
    void addPlayers_playerOfAnotherTeam_throwsAndSavesNothing() {
        // given: KIA 를 응원하는데 LG 선수가 섞였다
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(2L, 20L)))
                .willReturn(List.of(playerOf(2L, "김도영", KIA), playerOf(20L, "김현수", LG)));

        // when & then
        assertThatThrownBy(() -> supportService.addPlayers(ACCOUNT_ID, List.of(2L, 20L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAYER_NOT_IN_SUPPORT_TEAM);

        verify(userSupportPlayerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-14, USER-SP-23] 요청한 선수를 기존 응원에 더하고(전체 교체 아님) 현재 응원 중인 선수 전체를 반환한다")
    void addPlayers_addsToExistingSupportAndReturnsAll() {
        // given: 이미 2번을 응원 중이고 3번을 추가한다
        Player existing = playerOf(2L, "김도영", KIA);
        Player added = playerOf(3L, "양현종", KIA);
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(3L))).willReturn(List.of(added));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 3L))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(playerRepository.getReferenceById(3L)).willReturn(added);
        given(userSupportPlayerRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(supportPlayerOf(existing), supportPlayerOf(added)));
        given(playerRepository.findAllByIdInOrderByNameAsc(List.of(2L, 3L)))
                .willReturn(List.of(existing, added));

        // when
        List<PlayerResponse> result = supportService.addPlayers(ACCOUNT_ID, List.of(3L));

        // then: 기존 2번이 그대로 남아 있다(추가 방식)
        assertThat(result).containsExactly(
                responseOf(2L, "김도영", KIA),
                responseOf(3L, "양현종", KIA));
    }

    @Test
    @DisplayName("[USER-SP-18] 이미 응원 중인 선수를 다시 추가하면 새 행을 만들지 않는다(멱등)")
    void addPlayers_alreadySupported_isNoOp() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        UserSupportPlayer active = supportPlayerOf(player);
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.of(active));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(active));
        given(playerRepository.findAllByIdInOrderByNameAsc(List.of(2L))).willReturn(List.of(player));

        // when
        supportService.addPlayers(ACCOUNT_ID, List.of(2L));

        // then
        assertThat(active.isOpposed()).isFalse();
        verify(userSupportPlayerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-19] 과거에 취소했던 선수를 다시 추가하면 새 행을 만들지 않고 기존 행을 재활성한다"
            + "(새 행을 만들면 UNIQUE 위반 500)")
    void addPlayers_previouslyOpposed_reactivatesExistingRow() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        UserSupportPlayer opposed = supportPlayerOf(player);
        opposed.oppose(LocalDateTime.of(2026, 7, 1, 0, 0));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.of(opposed));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(opposed));
        given(playerRepository.findAllByIdInOrderByNameAsc(List.of(2L))).willReturn(List.of(player));

        // when
        supportService.addPlayers(ACCOUNT_ID, List.of(2L));

        // then
        assertThat(opposed.isOpposed()).isFalse();
        verify(userSupportPlayerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-20] playerIds의 중복은 400이 아니라 제거 후 처리되어 조회·저장이 각 id당 1회만 일어난다")
    void addPlayers_duplicateIds_areDeduplicated() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.empty());
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(playerRepository.getReferenceById(2L)).willReturn(player);
        given(userSupportPlayerRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(supportPlayerOf(player)));
        given(playerRepository.findAllByIdInOrderByNameAsc(List.of(2L))).willReturn(List.of(player));

        // when
        supportService.addPlayers(ACCOUNT_ID, List.of(2L, 2L, 2L));

        // then: 중복이 제거되지 않았다면 findAllById(List.of(2L))가 스텁과 어긋나 실패한다
        verify(userSupportPlayerRepository).save(any(UserSupportPlayer.class));
    }

    @Test
    @DisplayName("[USER-SP-21] playerIds가 빈 배열이면 아무 변경 없이 현재 응원 선수 목록을 반환한다")
    void addPlayers_emptyList_isNoOp() {
        // given
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = supportService.addPlayers(ACCOUNT_ID, List.of());

        // then
        assertThat(result).isEmpty();
        verify(playerRepository, never()).findAllById(any());
        verify(userSupportPlayerRepository, never()).save(any());
    }

    // ---------- 응원 선수 취소 ----------

    @Test
    @DisplayName("[USER-SP-24] 취소는 행을 삭제하지 않고 oppose에 시각을 채운다")
    void opposePlayers_fillsOpposeWithoutDeletingRow() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        UserSupportPlayer active = supportPlayerOf(player);
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.of(active));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = supportService.opposePlayers(ACCOUNT_ID, List.of(2L));

        // then
        assertThat(active.isOpposed()).isTrue();
        assertThat(active.getOppose()).isNotNull();
        assertThat(result).isEmpty();
        verify(userSupportPlayerRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[USER-SP-25] 이미 취소된 선수를 다시 취소하면 최초 취소 시각을 보존한다(멱등)")
    void opposePlayers_alreadyOpposed_preservesFirstTimestamp() {
        // given
        LocalDateTime firstOppose = LocalDateTime.of(2026, 7, 1, 0, 0);
        Player player = playerOf(2L, "김도영", KIA);
        UserSupportPlayer opposed = supportPlayerOf(player);
        opposed.oppose(firstOppose);
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.of(opposed));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());

        // when
        supportService.opposePlayers(ACCOUNT_ID, List.of(2L));

        // then
        assertThat(opposed.getOppose()).isEqualTo(firstOppose);
    }

    @Test
    @DisplayName("[USER-SP-26] 취소 요청에 존재하지 않는 선수가 섞이면 PLAYER_NOT_FOUND(404)이며 같은 요청의 다른 취소도 반영되지 않는다")
    void opposePlayers_unknownPlayer_throwsAndChangesNothing() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        UserSupportPlayer active = supportPlayerOf(player);
        given(playerRepository.findAllById(List.of(2L, 999L))).willReturn(List.of(player));

        // when & then
        assertThatThrownBy(() -> supportService.opposePlayers(ACCOUNT_ID, List.of(2L, 999L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PLAYER_NOT_FOUND);

        assertThat(active.isOpposed()).isFalse();
    }

    @Test
    @DisplayName("[USER-SP-27] 존재하는 선수지만 응원한 적이 없으면 404가 아니라 아무 변경 없이 성공한다")
    void opposePlayers_neverSupportedPlayer_succeedsWithoutChange() {
        // given: 선수는 실재하지만 응원 행이 없다
        Player player = playerOf(2L, "김도영", KIA);
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.empty());
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());

        // when
        List<PlayerResponse> result = supportService.opposePlayers(ACCOUNT_ID, List.of(2L));

        // then
        assertThat(result).isEmpty();
        verify(userSupportPlayerRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-28] 선수를 전원 취소해도 응원 구단은 건드리지 않는다")
    void opposePlayers_doesNotTouchSupportTeam() {
        // given
        Player player = playerOf(2L, "김도영", KIA);
        given(playerRepository.findAllById(List.of(2L))).willReturn(List.of(player));
        given(userSupportPlayerRepository.findByUserAccount_IdAndPlayer_Id(ACCOUNT_ID, 2L))
                .willReturn(Optional.of(supportPlayerOf(player)));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());

        // when
        supportService.opposePlayers(ACCOUNT_ID, List.of(2L));

        // then: 구단 리포지토리를 아예 만지지 않는다
        verify(userSupportTeamRepository, never()).findByUserAccount_IdAndOpposeIsNull(any());
        verify(userSupportTeamRepository, never()).save(any());
    }

    @Test
    @DisplayName("[USER-SP-29] 남아 있는 응원 선수를 name 오름차순으로 반환하며, 응원 행마다 선수를 조회하지 않고 한 번에 가져온다")
    void currentSupportedPlayers_returnsNameAscWithSingleBatchQuery() {
        // given
        Player kimDoYoung = playerOf(2L, "김도영", KIA);
        Player yangHyeonJong = playerOf(3L, "양현종", KIA);
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(List.of(supportPlayerOf(yangHyeonJong), supportPlayerOf(kimDoYoung)));
        given(playerRepository.findAllByIdInOrderByNameAsc(List.of(3L, 2L)))
                .willReturn(List.of(kimDoYoung, yangHyeonJong));

        // when
        List<PlayerResponse> result = supportService.currentSupportedPlayers(ACCOUNT_ID);

        // then: 정렬은 DB(스텁)가 준 순서를 그대로 흘려보낸다
        assertThat(result).containsExactly(
                responseOf(2L, "김도영", KIA),
                responseOf(3L, "양현종", KIA));
        // 선수 조회는 batch 1회뿐 — 응원 행 수만큼 findById가 나가지 않는다
        verify(playerRepository, never()).findById(any());
    }
}
