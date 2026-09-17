package com.skhynix.user.cleanup.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * user 앱의 스케줄링을 켜는 <b>유일한</b> 자리. 이 모듈의 첫 스케줄러라 활성화 장치가 애초에 없었다
 * (quiz 의 것은 그 앱의 컴포넌트 스캔 범위라 여기엔 적용되지 않는다).
 *
 * <p>⚠ 이 설정이 없으면 {@code @Scheduled} 가 붙어 있어도 <b>아무 일도 일어나지 않고 에러도 나지
 * 않는다</b> — 정리가 안 도는 것을 알아챌 방법이 로그의 부재뿐이라, 가장 눈치채기 어려운 형태로
 * 고장난다. 지우지 말 것.
 *
 * <p><b>활성화 조건과 잡별 실행 조건은 다르다.</b> 종전에는 이 클래스가 만료 데이터 정리 스위치
 * 하나에 걸려 있었는데, 그러면 잡이 둘 이상이 되는 순간 "temp 정리만 켠 환경에서는 스케줄링 자체가
 * 없어 새 잡이 조용히 안 도는" 함정이 생긴다(위와 똑같이 에러 없는 고장이다). 그래서 여기는
 * <b>잡 스위치 중 하나라도 켜져 있으면</b> 활성화하고, 무엇이 실제로 도는지는 각 스케줄러 빈이
 * 자기 {@code @ConditionalOnProperty} 로 정한다.
 *
 * <p>⚠ 이 앱에 {@code @Scheduled} 빈을 새로 추가하면 그 잡의 스위치도 아래 목록에 넣어야 한다 —
 * 넣지 않으면 다른 잡이 전부 꺼진 환경에서 그 잡만 켰을 때 같은 함정을 다시 밟는다.
 */
@Configuration
@EnableScheduling
@Conditional(CleanupSchedulingConfig.AnyCleanupJobEnabled.class)
public class CleanupSchedulingConfig {

    /**
     * {@code @ConditionalOnProperty} 는 여러 조건의 OR 를 표현하지 못한다 — 중첩 조건을 OR 로 묶는
     * {@link AnyNestedCondition} 이 그 자리를 대신한다. 각 스위치의 기본값은 "없으면 꺼짐"이라
     * 아무 설정도 없는 환경에서는 종전처럼 스케줄링이 켜지지 않는다.
     */
    static class AnyCleanupJobEnabled extends AnyNestedCondition {

        AnyCleanupJobEnabled() {
            // 조건이 @Configuration 클래스 자체의 등록 여부를 가르므로 파싱 단계에서 판정해야 한다.
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = "user.cleanup.expired-data", name = "enabled",
                havingValue = "true")
        static class ExpiredDataCleanupEnabled {
        }

        @ConditionalOnProperty(prefix = "user.cleanup.temp-profile-image", name = "enabled",
                havingValue = "true")
        static class TempProfileImageCleanupEnabled {
        }
    }
}
