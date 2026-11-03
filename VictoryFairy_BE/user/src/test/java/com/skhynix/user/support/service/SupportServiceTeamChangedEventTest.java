package com.skhynix.user.support.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.player.repository.PlayerRepository;
import com.skhynix.domain.support.entity.UserSupportTeam;
import com.skhynix.domain.support.repository.UserSupportPlayerRepository;
import com.skhynix.domain.support.repository.UserSupportTeamRepository;
import com.skhynix.domain.team.entity.Team;
import com.skhynix.domain.team.repository.TeamRepository;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.support.event.SupportTeamChangedEvent;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code selectTeam} 이 {@link SupportTeamChangedEvent} 를 언제 내고 언제 내지 않는지 고정한다(서비스 쪽 절반).
 * 커밋 후 발행·롤백 시 미발행은 {@code SupportTeamChangedAfterCommitIntegrationTest},
 * 채널·payload·실패 비전파는 {@code SupportTeamChangedSignalPublisherTest} 가 맡는다.
 * 요구사항: {@code docs/requirements/user/support-selection.md} USER-SP-47, 48, 49.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportServiceTeamChangedEventTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Team KIA = teamOf(6L, "KIA", "HT");
    private static final Team LG = teamOf(8L, "LG", "LG");

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SupportService supportService;

    @BeforeEach
    void stubCommonCollaborators() {
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.of(account()));
        given(userAccountRepository.getReferenceById(ACCOUNT_ID)).willReturn(account());
        given(teamRepository.findById(6L)).willReturn(Optional.of(KIA));
        given(teamRepository.findById(8L)).willReturn(Optional.of(LG));
        given(userSupportPlayerRepository.findAllByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Collections.emptyList());
        given(userSupportTeamRepository.save(any())).willAnswer(call -> call.getArgument(0));
    }

    private static Team teamOf(Long id, String name, String code) {
        Team team = Team.builder().name(name).code(code).build();
        ReflectionTestUtils.setField(team, "id", id);
        return team;
    }

    private static UserAccount account() {
        return UserAccount.builder().nickname("응원러").password("encoded").build();
    }

    private static UserSupportTeam supportTeamOf(Team team) {
        return UserSupportTeam.builder().userAccount(account()).team(team).build();
    }

    @Test
    @DisplayName("[USER-SP-47] 기존 응원 구단과 다른 구단을 선택하면 해당 계정 id 를 담은 이벤트를 정확히 1회 발행한다")
    void selectTeam_differentTeam_publishesEventOnce() {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 8L))
                .willReturn(Optional.empty());

        supportService.selectTeam(ACCOUNT_ID, 8L);

        verify(eventPublisher, times(1)).publishEvent(new SupportTeamChangedEvent(ACCOUNT_ID));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("[USER-SP-47] 과거에 취소했던 구단으로 되돌아가는 재활성(A->B->A)도 이벤트를 1회 발행한다")
    void selectTeam_reactivatingOpposedTeam_publishesEventOnce() {
        UserSupportTeam opposedKia = supportTeamOf(KIA);
        opposedKia.oppose(LocalDateTime.now().minusDays(1));
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(LG)));
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 6L))
                .willReturn(Optional.of(opposedKia));

        supportService.selectTeam(ACCOUNT_ID, 6L);

        verify(eventPublisher, times(1)).publishEvent(new SupportTeamChangedEvent(ACCOUNT_ID));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("[USER-SP-49] 응원 이력이 없는 계정의 첫 구단 선택에서는 이벤트를 발행하지 않는다")
    void selectTeam_firstSelection_doesNotPublish() {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(userSupportTeamRepository.findByUserAccount_IdAndTeam_Id(ACCOUNT_ID, 6L))
                .willReturn(Optional.empty());

        supportService.selectTeam(ACCOUNT_ID, 6L);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("[USER-SP-49] 이미 응원 중인 구단을 다시 선택하면 이벤트를 발행하지 않는다")
    void selectTeam_sameTeam_doesNotPublish() {
        given(userSupportTeamRepository.findByUserAccount_IdAndOpposeIsNull(ACCOUNT_ID))
                .willReturn(Optional.of(supportTeamOf(KIA)));

        supportService.selectTeam(ACCOUNT_ID, 6L);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("[USER-SP-48] 없는 구단 id(TEAM_NOT_FOUND)로 실패하면 이벤트를 발행하지 않는다")
    void selectTeam_unknownTeam_doesNotPublish() {
        given(teamRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> supportService.selectTeam(ACCOUNT_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TEAM_NOT_FOUND);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("[USER-SP-48] 계정이 사라져 UNAUTHENTICATED 로 실패하면 이벤트를 발행하지 않는다")
    void selectTeam_accountMissing_doesNotPublish() {
        given(userAccountRepository.findWithLockById(ACCOUNT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> supportService.selectTeam(ACCOUNT_ID, 8L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("[USER-SP-48] 계정 락 대기 초과(락 획득 예외)로 실패하면 예외가 그대로 전파되고 이벤트를 발행하지 않는다")
    void selectTeam_lockTimeout_doesNotPublish() {
        given(userAccountRepository.findWithLockById(ACCOUNT_ID))
                .willThrow(new PessimisticLockingFailureException("lock wait timeout"));

        assertThatThrownBy(() -> supportService.selectTeam(ACCOUNT_ID, 8L))
                .isInstanceOf(PessimisticLockingFailureException.class);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
