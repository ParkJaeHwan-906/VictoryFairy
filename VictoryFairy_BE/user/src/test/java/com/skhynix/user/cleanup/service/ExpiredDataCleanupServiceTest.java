package com.skhynix.user.cleanup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.domain.user.entity.User;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.ExpiredAccountView;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.cleanup.policy.UnknownAccountPolicy;
import com.skhynix.user.cleanup.support.QuizLikeDeleteRuleInspector;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ExpiredDataCleanupService} 단위 테스트 — 협력자 3개(리포지토리·이레이저·FK 검사기)를 전부 목으로
 * 대체한다. 요구사항: {@code docs/requirements/user/expired-data-cleanup.md}.
 *
 * <p>협력자를 하나라도 빠뜨리면 컴파일은 통과하고 런타임 NPE로만 깨진다는 함정이 지시에 명시돼 있어
 * {@code @InjectMocks} 대신 {@link #serviceOf}로 생성자를 직접 호출한다 — 셋 중 하나가 빠지면 이 헬퍼
 * 자체가 컴파일 에러가 되어 함정을 원천 차단한다.
 */
@ExtendWith(MockitoExtension.class)
class ExpiredDataCleanupServiceTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 18, 3, 0, 0);
    private static final LocalDateTime EXPECTED_THRESHOLD = LocalDateTime.of(2026, 7, 19, 3, 0, 0);

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private ExpiredAccountEraser eraser;

    @Mock
    private QuizLikeDeleteRuleInspector quizLikeDeleteRuleInspector;

    private ExpiredDataCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ExpiredDataCleanupService(userAccountRepository, eraser, quizLikeDeleteRuleInspector);
    }

    private UserAccount unknownAccount() {
        User user = User.builder().name("UNKNOWN").tel("00000000001")
                .email(UnknownAccountPolicy.EMAIL).gender(com.skhynix.domain.user.entity.Gender.MALE)
                .build();
        return UserAccount.reserved(UnknownAccountPolicy.UID, user, UnknownAccountPolicy.NICKNAME,
                UnknownAccountPolicy.LOCKED_PASSWORD);
    }

    private void stubHappyPathPreconditions() {
        given(quizLikeDeleteRuleInspector.isSetNull()).willReturn(true);
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID))
                .willReturn(Optional.of(unknownAccount()));
    }

    @Test
    @DisplayName("[USER-EDC-3, USER-EDC-7] 대상 조회는 '기준 시각 - 30일'을 임계값으로, 더미 계정 uid를 "
            + "제외값으로 넘긴다 — 30일 경계 판정의 실제 입력이 여기서 결정된다")
    void removeExpiredData_queriesExpiredAccounts_withThresholdThirtyDaysBeforeBaseTime() {
        // given
        stubHappyPathPreconditions();
        given(userAccountRepository.findExpiredAccounts(any(), anyString())).willReturn(List.of());

        // when
        service.removeExpiredData(BASE_TIME);

        // then
        verify(userAccountRepository).findExpiredAccounts(EXPECTED_THRESHOLD, UnknownAccountPolicy.UID);
    }

    @Test
    @DisplayName("[USER-EDC-18] 삭제 대상이 0건이면 예외 없이 만료 토큰 삭제 단계로 진행한다")
    void removeExpiredData_noTargets_proceedsToTokenPurgeWithoutException() {
        // given
        stubHappyPathPreconditions();
        given(userAccountRepository.findExpiredAccounts(any(), anyString())).willReturn(List.of());
        given(eraser.purgeExpiredTokens(BASE_TIME)).willReturn(0);

        // when / then
        assertThatCode(() -> service.removeExpiredData(BASE_TIME)).doesNotThrowAnyException();
        verify(eraser, never()).erase(any(), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-42] 더미 계정이 없으면 계정 삭제 단계를 건너뛰지만(erase 미호출) "
            + "만료 토큰 삭제는 계속 수행된다")
    void removeExpiredData_unknownAccountMissing_skipsAccountDeletion_butStillPurgesTokens() {
        // given
        given(quizLikeDeleteRuleInspector.isSetNull()).willReturn(true);
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID)).willReturn(Optional.empty());
        given(eraser.purgeExpiredTokens(BASE_TIME)).willReturn(5);

        // when
        service.removeExpiredData(BASE_TIME);

        // then
        verify(userAccountRepository, never()).findExpiredAccounts(any(), anyString());
        verify(eraser, never()).erase(any(), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-49] quizzes_like FK가 아직 SET NULL이 아니면 계정 삭제 단계를 건너뛰지만"
            + "(erase 미호출, 더미 계정 조회조차 안 함) 만료 토큰 삭제는 계속 수행된다")
    void removeExpiredData_fkNotSetNullYet_skipsAccountDeletion_butStillPurgesTokens() {
        // given
        given(quizLikeDeleteRuleInspector.isSetNull()).willReturn(false);
        given(eraser.purgeExpiredTokens(BASE_TIME)).willReturn(3);

        // when
        service.removeExpiredData(BASE_TIME);

        // then
        verify(userAccountRepository, never()).findByUid(anyString());
        verify(userAccountRepository, never()).findExpiredAccounts(any(), anyString());
        verify(eraser, never()).erase(any(), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-14, USER-EDC-15] 대상 3건 중 2번째 처리가 예외로 실패해도 1·3번째는 "
            + "그대로 처리되고 회차는 정상 종료된다(이미 처리된 계정을 롤백하지 않는다)")
    void removeExpiredData_middleTargetFails_othersStillProcessed_andRunCompletes() {
        // given
        stubHappyPathPreconditions();
        ExpiredAccountView target1 = new ExpiredAccountView(1L, "uid-1", 11L);
        ExpiredAccountView target2 = new ExpiredAccountView(2L, "uid-2", 12L);
        ExpiredAccountView target3 = new ExpiredAccountView(3L, "uid-3", 13L);
        given(userAccountRepository.findExpiredAccounts(any(), anyString()))
                .willReturn(List.of(target1, target2, target3));
        given(eraser.erase(eq(target1), any())).willReturn(new AccountEraseResult(0, 0, 0, true));
        willThrow(new RuntimeException("boom")).given(eraser).erase(eq(target2), any());
        given(eraser.erase(eq(target3), any())).willReturn(new AccountEraseResult(0, 0, 0, true));

        // when / then
        assertThatCode(() -> service.removeExpiredData(BASE_TIME)).doesNotThrowAnyException();
        verify(eraser, times(3)).erase(any(), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("이미 다른 파드가 처리해 지울 users 행이 없는 결과(accountRemoved=false)는 실패로 "
            + "취급되지 않고 다음 대상 처리를 그대로 이어간다")
    void removeExpiredData_alreadyRemovedByOtherPod_doesNotStopProcessingRemainingTargets() {
        // given
        stubHappyPathPreconditions();
        ExpiredAccountView target1 = new ExpiredAccountView(1L, "uid-1", 11L);
        ExpiredAccountView target2 = new ExpiredAccountView(2L, "uid-2", 12L);
        given(userAccountRepository.findExpiredAccounts(any(), anyString()))
                .willReturn(List.of(target1, target2));
        given(eraser.erase(eq(target1), any())).willReturn(new AccountEraseResult(0, 0, 0, false));
        given(eraser.erase(eq(target2), any())).willReturn(new AccountEraseResult(0, 0, 0, true));

        // when
        assertThatCode(() -> service.removeExpiredData(BASE_TIME)).doesNotThrowAnyException();

        // then
        verify(eraser, times(2)).erase(any(), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-23] 만료 토큰 삭제가 실패해도 예외가 호출자에게 전파되지 않고, "
            + "그 실패가 이미 완료된 계정 처리 결과에 영향을 주지 않는다")
    void removeExpiredData_tokenPurgeFails_doesNotPropagate_andAccountProcessingAlreadyDone() {
        // given
        stubHappyPathPreconditions();
        ExpiredAccountView target = new ExpiredAccountView(1L, "uid-1", 11L);
        given(userAccountRepository.findExpiredAccounts(any(), anyString())).willReturn(List.of(target));
        given(eraser.erase(eq(target), any())).willReturn(new AccountEraseResult(1, 2, 3, true));
        willThrow(new RuntimeException("token purge failed")).given(eraser).purgeExpiredTokens(BASE_TIME);

        // when / then
        assertThatCode(() -> service.removeExpiredData(BASE_TIME)).doesNotThrowAnyException();
        verify(eraser).erase(eq(target), any());
        verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("[USER-EDC-21] 한 회차 안에서 계정 처리(대상 조회·erase)가 만료 토큰 삭제보다 먼저 실행된다")
    void removeExpiredData_processesAccountsBeforePurgingTokens() {
        // given
        stubHappyPathPreconditions();
        ExpiredAccountView target = new ExpiredAccountView(1L, "uid-1", 11L);
        given(userAccountRepository.findExpiredAccounts(any(), anyString())).willReturn(List.of(target));
        given(eraser.erase(eq(target), any())).willReturn(new AccountEraseResult(0, 0, 0, true));

        // when
        service.removeExpiredData(BASE_TIME);

        // then
        InOrder inOrder = inOrder(userAccountRepository, eraser);
        inOrder.verify(userAccountRepository).findExpiredAccounts(any(), anyString());
        inOrder.verify(eraser).erase(eq(target), any());
        inOrder.verify(eraser).purgeExpiredTokens(BASE_TIME);
    }

    @Test
    @DisplayName("erase 호출에 넘기는 더미 계정 인자가 findByUid로 조회한 그 계정과 동일 인스턴스다")
    void removeExpiredData_passesResolvedUnknownAccountInstanceToErase() {
        // given
        UserAccount unknown = unknownAccount();
        given(quizLikeDeleteRuleInspector.isSetNull()).willReturn(true);
        given(userAccountRepository.findByUid(UnknownAccountPolicy.UID)).willReturn(Optional.of(unknown));
        ExpiredAccountView target = new ExpiredAccountView(1L, "uid-1", 11L);
        given(userAccountRepository.findExpiredAccounts(any(), anyString())).willReturn(List.of(target));
        given(eraser.erase(eq(target), any())).willReturn(new AccountEraseResult(0, 0, 0, true));

        // when
        service.removeExpiredData(BASE_TIME);

        // then
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(eraser).erase(eq(target), captor.capture());
        assertThat(captor.getValue()).isSameAs(unknown);
    }
}
