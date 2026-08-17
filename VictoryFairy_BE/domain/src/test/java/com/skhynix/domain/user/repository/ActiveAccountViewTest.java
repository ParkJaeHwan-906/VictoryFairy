package com.skhynix.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ActiveAccountView#acceptsTokenIssuedAt(long)}이 {@code UserAccount.acceptsTokenIssuedAt}로
 * 위임하는지만 검증하는 순수 단위 테스트. 판정 규칙 자체(세 경계 값)는
 * {@code UserAccountPasswordChangeTest}가 단일 출처에서 이미 고정하므로, 여기서는 <b>프로젝션이 필드를
 * 그대로 실어 나르는지</b>만 확인한다(별도 비교식을 다시 쓰지 않는다는 계약).
 * 요구사항: {@code docs/requirements/user/access-token-invalidation.md}(USER-ATI-4, 8, 9).
 */
class ActiveAccountViewTest {

    @Test
    @DisplayName("[USER-ATI-9] passwordChangedEpochSecond가 NULL인 프로젝션은 iat가 무엇이든 통과한다")
    void acceptsTokenIssuedAt_nullBaseline_accepts() {
        // given
        ActiveAccountView view = new ActiveAccountView(1L, null);

        // when & then
        assertThat(view.acceptsTokenIssuedAt(0L)).isTrue();
        assertThat(view.acceptsTokenIssuedAt(Long.MIN_VALUE)).isTrue();
    }

    @Test
    @DisplayName("[USER-ATI-8] iat가 기준 시각과 같은 초이면 통과하고, 1초 앞서면 거부한다")
    void acceptsTokenIssuedAt_boundarySeconds() {
        // given
        long baseline = 1_755_400_000L;
        ActiveAccountView view = new ActiveAccountView(1L, baseline);

        // when & then
        assertThat(view.acceptsTokenIssuedAt(baseline)).isTrue();
        assertThat(view.acceptsTokenIssuedAt(baseline - 1)).isFalse();
        assertThat(view.acceptsTokenIssuedAt(baseline + 1)).isTrue();
    }

    @Test
    @DisplayName("record 접근자는 생성자에 넘긴 id·passwordChangedEpochSecond를 그대로 반환한다")
    void accessors_returnConstructorArgumentsAsIs() {
        // given
        ActiveAccountView view = new ActiveAccountView(42L, 1_700_000_000L);

        // when & then
        assertThat(view.id()).isEqualTo(42L);
        assertThat(view.passwordChangedEpochSecond()).isEqualTo(1_700_000_000L);
    }
}
