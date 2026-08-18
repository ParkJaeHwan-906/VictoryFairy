package com.skhynix.user.auth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NicknameChangeCooldownPolicy}(닉네임 재변경 간격 30일 정책의 단일 출처)를 순수 함수 수준에서
 * 검증한다. DB·스프링 컨텍스트 없음. 요구사항: {@code docs/requirements/user/profile-edit.md}
 * (USER-PE-40~49).
 *
 * <p>판정 자체는 {@link LocalDateTime}(존이 없는 벽시계 값) 두 개를 비교하는 순수 계산이라 이 테스트
 * 수준에서는 "시간대와 무관함"이 자명하다(내부적으로 어떤 존·시스템 시계도 참조하지 않는다) — 실제
 * 시간대 회귀는 값을 만드는 쪽(호출자 서비스, {@code Clock} 빈)에서 지켜야 하며 그 증명은
 * {@code UserProfileEditServiceTest}가 맡는다.
 */
class NicknameChangeCooldownPolicyTest {

    @Test
    @DisplayName("[USER-PE-44] 쿨다운 기간은 30일이다(COOLDOWN_DAYS가 단일 출처)")
    void cooldownDays_isThirty() {
        assertThat(NicknameChangeCooldownPolicy.COOLDOWN_DAYS).isEqualTo(30);
    }

    @Test
    @DisplayName("[USER-PE-44] 마지막 변경으로부터 정확히 30일이 지난 시점은 경계가 허용 쪽이라 쿨다운 중이 아니다")
    void isCoolingDown_exactlyThirtyDaysElapsed_isFalse() {
        LocalDateTime lastChanged = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        LocalDateTime now = lastChanged.plusDays(30);

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)).isFalse();
    }

    @Test
    @DisplayName("[USER-PE-44] 마지막 변경으로부터 30일-1초(1초 모자람) 지난 시점은 여전히 쿨다운 중이다")
    void isCoolingDown_oneSecondShortOfThirtyDays_isTrue() {
        LocalDateTime lastChanged = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        LocalDateTime now = lastChanged.plusDays(30).minusSeconds(1);

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)).isTrue();
    }

    @Test
    @DisplayName("[USER-PE-45] lastChangedAt이 NULL이면(한 번도 안 바꿨거나 컬럼 도입 이전) "
            + "현재 시각과 무관하게 쿨다운이 아니다")
    void isCoolingDown_nullLastChangedAt_isAlwaysFalse() {
        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(null, LocalDateTime.MIN)).isFalse();
        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(null, LocalDateTime.MAX)).isFalse();
    }

    @Test
    @DisplayName("방금 바꾼 직후(경과 0초)는 쿨다운 중이다")
    void isCoolingDown_justChangedNow_isTrue() {
        LocalDateTime lastChanged = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, lastChanged)).isTrue();
    }

    @Test
    @DisplayName("쿨다운 기간이 한참 지난 시점(30일+1일)은 쿨다운 중이 아니다")
    void isCoolingDown_wellPastCooldown_isFalse() {
        LocalDateTime lastChanged = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        LocalDateTime now = lastChanged.plusDays(31);

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)).isFalse();
    }

    @Test
    @DisplayName("[USER-PE-43] nextChangeableAt은 마지막 변경 시각 + 30일을 가리키는 LocalDateTime이다")
    void nextChangeableAt_returnsLastChangedPlusThirtyDays() {
        LocalDateTime lastChanged = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        LocalDateTime result = NicknameChangeCooldownPolicy.nextChangeableAt(lastChanged);

        assertThat(result).isEqualTo(lastChanged.plusDays(30));
    }
}
