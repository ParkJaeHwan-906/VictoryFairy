package com.skhynix.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link UserAccountService#withdraw(Long)}을 협력 객체(리포지토리)를 목으로 대체해 단위로 검증한다.
 * DB·스프링 컨텍스트 없음.
 */
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserRefreshTokenRepository userRefreshTokenRepository;

    @InjectMocks
    private UserAccountService userAccountService;

    private UserAccount newActiveAccount() {
        return UserAccount.builder()
                .user(null)
                .nickname("nickname")
                .password("encoded-password")
                .build();
    }

    @Test
    @DisplayName("[USER-WD-1, USER-WD-3] 활성 계정을 탈퇴 처리하면 exitAt이 기록되고 같은 시각으로 refresh 토큰이 전부 만료된다")
    void withdraw_activeAccount_setsExitAtAndExpiresRefreshTokensWithSameTimestamp() {
        // given
        Long accountId = 1L;
        UserAccount account = newActiveAccount();
        given(userAccountRepository.findById(accountId)).willReturn(Optional.of(account));

        // when
        userAccountService.withdraw(accountId);

        // then
        assertThat(account.isWithdrawn()).isTrue();
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRefreshTokenRepository).expireValidTokens(eq(account), nowCaptor.capture());
        assertThat(account.getExitAt()).isEqualTo(nowCaptor.getValue());
    }

    @Test
    @DisplayName("존재하지 않는(또는 이미 사라진) 계정 id로 탈퇴를 시도하면 UNAUTHENTICATED 예외가 발생하고 토큰 만료는 호출되지 않는다")
    void withdraw_accountNotFound_throwsUnauthenticatedAndSkipsTokenExpiry() {
        // given
        Long accountId = 999L;
        given(userAccountRepository.findById(accountId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userAccountService.withdraw(accountId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHENTICATED);

        verify(userRefreshTokenRepository, never()).expireValidTokens(any(), any());
    }

    @Test
    @DisplayName("[USER-WD-4] 이미 탈퇴한 계정을 다시 탈퇴 처리해도 최초 exitAt이 갱신되지 않는다"
            + "(정상 경로에서는 필터가 먼저 차단하지만, 서비스 계층도 엔티티의 가드를 통해 방어함을 확인)")
    void withdraw_alreadyWithdrawnAccount_doesNotOverwriteExitAt() {
        // given
        Long accountId = 1L;
        UserAccount account = newActiveAccount();
        LocalDateTime firstExitAt = LocalDateTime.now().minusDays(1);
        account.withdraw(firstExitAt);
        given(userAccountRepository.findById(accountId)).willReturn(Optional.of(account));

        // when
        userAccountService.withdraw(accountId);

        // then
        assertThat(account.getExitAt()).isEqualTo(firstExitAt);
        verify(userRefreshTokenRepository, times(1)).expireValidTokens(eq(account), any());
    }
}
