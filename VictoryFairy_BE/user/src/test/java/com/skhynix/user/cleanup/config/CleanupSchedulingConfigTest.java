package com.skhynix.user.cleanup.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link CleanupSchedulingConfig} — 스케줄링 활성화 조건이 "잡 스위치 중 하나라도 켜져 있으면"이라는
 * OR 조건임을 고정한다. 요구사항: {@code docs/requirements/user/profile-image.md} 해소 방침 1.
 *
 * <p>기존 동작(만료 데이터 정리 단독 스위치로도 활성화됨)이 한 글자도 안 바뀌었는지가 회귀 기준이다 —
 * {@code ExpiredDataCleanupSchedulerTest} 7건이 그린이어야 한다는 지시와 짝인 설정 레벨 증거.
 */
class CleanupSchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CleanupSchedulingConfig.class);

    @Test
    @DisplayName("아무 스위치도 없으면(기본값) 스케줄링 설정 빈이 등록되지 않는다")
    void noPropertiesSet_configNotRegistered() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CleanupSchedulingConfig.class));
    }

    @Test
    @DisplayName("[해소 방침 1] expired-data.enabled=true 단독으로도(종전과 동일하게) 스케줄링이 활성화된다")
    void expiredDataAloneEnabled_configRegistered() {
        runner.withPropertyValues("user.cleanup.expired-data.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CleanupSchedulingConfig.class));
    }

    @Test
    @DisplayName("[USER-PI-89] temp-profile-image.enabled=true 단독으로도 스케줄링이 활성화된다"
            + "(expired-data는 꺼진 채로) — 종전에는 이 조합에서 스케줄러 빈 자체가 없었다")
    void tempProfileImageAloneEnabled_configRegistered() {
        runner.withPropertyValues(
                        "user.cleanup.expired-data.enabled=false",
                        "user.cleanup.temp-profile-image.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CleanupSchedulingConfig.class));
    }

    @Test
    @DisplayName("둘 다 false면 스케줄링이 활성화되지 않는다")
    void bothDisabled_configNotRegistered() {
        runner.withPropertyValues(
                        "user.cleanup.expired-data.enabled=false",
                        "user.cleanup.temp-profile-image.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CleanupSchedulingConfig.class));
    }

    @Test
    @DisplayName("둘 다 true여도 스케줄링 설정 빈은 정확히 하나만 등록된다(중복 등록 없음)")
    void bothEnabled_configRegisteredOnce() {
        runner.withPropertyValues(
                        "user.cleanup.expired-data.enabled=true",
                        "user.cleanup.temp-profile-image.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(CleanupSchedulingConfig.class));
    }
}
