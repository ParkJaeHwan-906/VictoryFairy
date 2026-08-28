package com.skhynix.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAccount#deductPoint(long)} 의 잔액 가드를 다루는 순수 단위 테스트(Spring 컨텍스트/DB 없음).
 *
 * <p>여기서 지키려는 것은 <b>음수 잔액이 생기지 않는다</b>는 것 하나다. 아이템 구매가 이 뮤테이터의
 * 유일한 호출자이고, 서비스가 먼저 잔액을 검사해 4xx 를 돌려주므로 여기까지 오는 것은 그 검사를
 * 빠뜨린 코드 경로뿐이다 — 그래서 기대 예외가 {@code BusinessException} 이 아니라
 * {@code IllegalStateException} 이다(사용자에게 보여 줄 상황이 아니라 버그).
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
