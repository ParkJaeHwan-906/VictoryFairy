package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#withdraw(LocalDateTime)} / {@link UserAccount#isWithdrawn()}의 상태 전이
 * 규칙을 스프링 컨텍스트·DB 없이 순수 단위로 검증한다.
 *
 * <p>{@code user} 모듈은 {@code :domain}을 컴파일 의존성으로 가지므로, 엔티티가 위치한
 * {@code domain} 모듈에 별도 테스트 소스셋을 만들지 않고도 이 클래스를 직접 테스트할 수 있다
 * (요구사항 담당자 지시: domain/quiz에 새 테스트 소스셋을 만들지 말 것).
 */
class UserAccountWithdrawTest {

    private UserAccount newActiveAccount() {
        return UserAccount.builder()
                .user(null)
                .nickname("nickname")
                .password("encoded-password")
                .build();
    }

    @Test
    @DisplayName("[USER-WD-1] 활성 계정에 withdraw(now)를 호출하면 exitAt에 해당 시각이 기록되고 탈퇴 상태가 된다")
    void withdraw_activeAccount_setsExitAtToGivenTime() {
        // given
        UserAccount account = newActiveAccount();
        LocalDateTime now = LocalDateTime.of(2026, 7, 17, 12, 0, 0);
        assertThat(account.isWithdrawn()).isFalse();

        // when
        account.withdraw(now);

        // then
        assertThat(account.getExitAt()).isEqualTo(now);
        assertThat(account.isWithdrawn()).isTrue();
    }

    @Test
    @DisplayName("[USER-WD-4] 이미 탈퇴한 계정에 withdraw()를 다시 호출해도 최초 exitAt이 그대로 보존된다")
    void withdraw_alreadyWithdrawnAccount_preservesFirstExitAt() {
        // given
        UserAccount account = newActiveAccount();
        LocalDateTime firstExitAt = LocalDateTime.of(2026, 7, 17, 12, 0, 0);
        account.withdraw(firstExitAt);
        LocalDateTime secondAttempt = firstExitAt.plusDays(1);

        // when
        account.withdraw(secondAttempt);

        // then
        assertThat(account.getExitAt()).isEqualTo(firstExitAt);
        assertThat(account.getExitAt()).isNotEqualTo(secondAttempt);
    }

    @Test
    @DisplayName("exitAt이 없는 신규 계정은 isWithdrawn()이 false를 반환한다")
    void isWithdrawn_newAccount_returnsFalse() {
        // given
        UserAccount account = newActiveAccount();

        // then
        assertThat(account.isWithdrawn()).isFalse();
        assertThat(account.getExitAt()).isNull();
    }
}
