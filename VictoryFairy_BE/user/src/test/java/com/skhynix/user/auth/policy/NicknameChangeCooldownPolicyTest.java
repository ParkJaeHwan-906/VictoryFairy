package com.skhynix.user.auth.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NicknameChangeCooldownPolicy}(닉네임 재변경 간격 30일 정책의 단일 출처)를 순수 함수 수준에서
 * 검증한다. DB·스프링 컨텍스트 없음. 요구사항: {@code docs/requirements/user/profile-edit.md}
 * (USER-PE-40~49).
 */
class NicknameChangeCooldownPolicyTest {

    @Test
    @DisplayName("[USER-PE-44] 쿨다운 기간은 30일 = 2,592,000초다(리터럴이 아니라 COOLDOWN_DAYS에서 파생됨)")
    void cooldownSeconds_isThirtyDaysInSeconds() {
        assertThat(NicknameChangeCooldownPolicy.COOLDOWN_DAYS).isEqualTo(30);
        assertThat(NicknameChangeCooldownPolicy.COOLDOWN_SECONDS).isEqualTo(2_592_000L);
    }

    @Test
    @DisplayName("[USER-PE-44] 마지막 변경으로부터 정확히 COOLDOWN_SECONDS가 지난 시점은 경계가 허용 쪽이라 "
            + "쿨다운 중이 아니다")
    void isCoolingDown_exactlyCooldownSecondsElapsed_isFalse() {
        long lastChanged = 1_000_000L;
        long now = lastChanged + NicknameChangeCooldownPolicy.COOLDOWN_SECONDS;

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)).isFalse();
    }

    @Test
    @DisplayName("[USER-PE-44] 마지막 변경으로부터 COOLDOWN_SECONDS - 1초(1초 모자람) 지난 시점은 여전히 쿨다운 중이다")
    void isCoolingDown_oneSecondShortOfCooldown_isTrue() {
        long lastChanged = 1_000_000L;
        long now = lastChanged + NicknameChangeCooldownPolicy.COOLDOWN_SECONDS - 1;

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, now)).isTrue();
    }

    @Test
    @DisplayName("[USER-PE-45] lastChangedEpochSecond가 NULL이면(한 번도 안 바꿨거나 컬럼 도입 이전) "
            + "현재 시각과 무관하게 쿨다운이 아니다")
    void isCoolingDown_nullLastChanged_isAlwaysFalse() {
        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(null, 0L)).isFalse();
        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(null, Long.MAX_VALUE)).isFalse();
    }

    @Test
    @DisplayName("방금 바꾼 직후(경과 0초)는 쿨다운 중이다")
    void isCoolingDown_justChangedNow_isTrue() {
        long lastChanged = 1_000_000L;

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, lastChanged)).isTrue();
    }

    @Test
    @DisplayName("[USER-PE-43] nextChangeableAt은 마지막 변경 시각 + COOLDOWN_SECONDS를 가리키는 절대 시각(Instant)이다")
    void nextChangeableAt_returnsLastChangedPlusCooldownSecondsAsInstant() {
        long lastChanged = 1_000_000L;

        Instant result = NicknameChangeCooldownPolicy.nextChangeableAt(lastChanged);

        assertThat(result).isEqualTo(Instant.ofEpochSecond(lastChanged + NicknameChangeCooldownPolicy.COOLDOWN_SECONDS));
    }

    @Test
    @DisplayName("[USER-PE-49] 판정은 epoch 초 비교만으로 이뤄져 실행 환경의 시간대와 무관하다 — 같은 epoch 초 쌍이면 "
            + "그 시각을 UTC로 해석하든 KST로 해석하든(=존을 무엇으로 보든) 판정 결과와 nextChangeableAt이 가리키는 "
            + "절대 시각이 항상 같다")
    void isCoolingDownAndNextChangeableAt_areTimezoneIndependent() {
        // given: 같은 epoch 초를 서로 다른 존으로 "해석"만 해도(존 자체는 판정에 전달되지 않음) 값이 같다는 것을
        // Instant 동치로 증명한다.
        Instant instantUtc = Instant.parse("2026-08-17T03:00:00Z");
        Instant instantKstView = instantUtc.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        assertThat(instantUtc).isEqualTo(instantKstView);

        long lastChanged = instantUtc.getEpochSecond();
        long nowUtcView = instantUtc.getEpochSecond();
        long nowKstView = instantKstView.getEpochSecond();

        assertThat(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, nowUtcView))
                .isEqualTo(NicknameChangeCooldownPolicy.isCoolingDown(lastChanged, nowKstView));
        assertThat(NicknameChangeCooldownPolicy.nextChangeableAt(lastChanged))
                .isEqualTo(instantUtc.plusSeconds(NicknameChangeCooldownPolicy.COOLDOWN_SECONDS));
    }
}
