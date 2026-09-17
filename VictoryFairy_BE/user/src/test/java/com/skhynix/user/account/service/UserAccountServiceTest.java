package com.skhynix.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.domain.user.entity.UserAccount;
import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.domain.user.repository.UserRefreshTokenRepository;
import com.skhynix.user.account.event.UserWithdrawnEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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

    @Mock
    private ApplicationEventPublisher eventPublisher;

    // 서비스가 Clock 을 생성자로 받게 되어 @InjectMocks 대신 직접 생성한다(GameServiceTest 와 같은 방식).
    // Clock 을 목으로 두면 instant()/getZone() 이 null 을 돌려줘 LocalDateTime.now(clock) 에서 NPE 다.
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountService(
                userAccountRepository, userRefreshTokenRepository, clock, eventPublisher);
    }

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

    @Test
    @DisplayName("[USER-PI-74, PI-103] 탈퇴 시 프로필 이미지가 있던 계정이면 그 EP를 실은 "
            + "UserWithdrawnEvent가 발행된다(실제 삭제는 AFTER_COMMIT 리스너 몫 — 여기서는 이벤트 발행만 검증)")
    void withdraw_accountWithProfileImage_publishesEventWithEndpoint() {
        // given
        Long accountId = 1L;
        UserAccount account = newActiveAccount();
        account.changeProfileImgUrl("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
        given(userAccountRepository.findById(accountId)).willReturn(Optional.of(account));

        // when
        userAccountService.withdraw(accountId);

        // then
        ArgumentCaptor<UserWithdrawnEvent> eventCaptor =
                ArgumentCaptor.forClass(UserWithdrawnEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().profileImgUrl())
                .isEqualTo("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }

    @Test
    @DisplayName("[USER-PI-71 계열] 프로필 이미지가 없던 계정을 탈퇴시키면 profileImgUrl이 null인 "
            + "UserWithdrawnEvent가 그래도 발행된다")
    void withdraw_accountWithoutProfileImage_publishesEventWithNullEndpoint() {
        // given
        Long accountId = 1L;
        UserAccount account = newActiveAccount();
        given(userAccountRepository.findById(accountId)).willReturn(Optional.of(account));

        // when
        userAccountService.withdraw(accountId);

        // then
        ArgumentCaptor<UserWithdrawnEvent> eventCaptor =
                ArgumentCaptor.forClass(UserWithdrawnEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().profileImgUrl()).isNull();
    }

    @Test
    @DisplayName("[USER-PI-75] 탈퇴해도 계정 행의 profileImgUrl 컬럼 값은 비워지지 않는다"
            + "(soft delete라 행이 남고, 컬럼은 30일 뒤 하드 삭제로 행과 함께 사라진다)")
    void withdraw_doesNotClearProfileImgUrlColumn() {
        // given
        Long accountId = 1L;
        UserAccount account = newActiveAccount();
        account.changeProfileImgUrl("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
        given(userAccountRepository.findById(accountId)).willReturn(Optional.of(account));

        // when
        userAccountService.withdraw(accountId);

        // then
        assertThat(account.getProfileImgUrl())
                .isEqualTo("user-profile-img/9f1c1e2a-aaaa-4bbb-8ccc-1234567890ab.jpg");
    }

    @Test
    @DisplayName("존재하지 않는 계정으로 탈퇴를 시도하면 탈퇴 자체가 실패하므로 "
            + "UserWithdrawnEvent도 발행되지 않는다")
    void withdraw_accountNotFound_doesNotPublishEvent() {
        // given
        Long accountId = 999L;
        given(userAccountRepository.findById(accountId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userAccountService.withdraw(accountId))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(eventPublisher);
    }
}
