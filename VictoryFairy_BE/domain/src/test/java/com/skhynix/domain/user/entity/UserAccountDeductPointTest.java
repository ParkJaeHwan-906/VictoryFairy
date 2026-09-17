package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#deductPoint(long)} 의 잔액 가드. 지키려는 것은 <b>음수 잔액이 생기지 않는다</b>는
 * 것 하나이며, 기대 예외가 {@code IllegalStateException} 인 것은 여기까지 오는 경로가 서비스의 잔액
 * 검사를 빠뜨린 버그뿐이기 때문이다.
 */
class UserAccountDeductPointTest {

    // 잔액은 addPoint 로 만든다 — domain 모듈 테스트에는 spring-test(ReflectionTestUtils)가 없고,
    // 적립 → 차감이 실제 서비스에서 일어나는 순서이기도 하다.
    private static UserAccount accountWithPoint(long point) {
        UserAccount account = UserAccount.builder()
                .nickname("nickname")
                .password("encoded-password")
                .build();
        account.addPoint(point);
        return account;
    }

    @Test
    @DisplayName("보유 포인트보다 적은 금액은 그대로 차감된다")
    void deductPoint_withinBalance_subtracts() {
        UserAccount account = accountWithPoint(300L);

        account.deductPoint(100L);

        assertThat(account.getPoint()).isEqualTo(200L);
    }

    @Test
    @DisplayName("보유 포인트와 같은 금액은 차감되어 잔액이 0이 된다")
    void deductPoint_exactBalance_becomesZero() {
        UserAccount account = accountWithPoint(100L);

        account.deductPoint(100L);

        assertThat(account.getPoint()).isZero();
    }

    @Test
    @DisplayName("보유 포인트보다 큰 금액이면 예외를 던지고 잔액을 건드리지 않는다")
    void deductPoint_overBalance_throwsAndKeepsBalance() {
        UserAccount account = accountWithPoint(50L);

        assertThatThrownBy(() -> account.deductPoint(100L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(account.getPoint()).isEqualTo(50L);
    }

    @Test
    @DisplayName("음수 차감은 적립으로 둔갑하므로 예외를 던진다")
    void deductPoint_negativeDelta_throws() {
        UserAccount account = accountWithPoint(50L);

        assertThatThrownBy(() -> account.deductPoint(-100L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(account.getPoint()).isEqualTo(50L);
    }
}
