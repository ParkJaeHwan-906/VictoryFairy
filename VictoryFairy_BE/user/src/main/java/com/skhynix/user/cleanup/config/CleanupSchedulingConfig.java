package com.skhynix.user.cleanup.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>실행 스위치와 같은 조건을 걸어, 꺼진 환경에서는 스케줄러 스레드조차 만들지 않는다.
 * ⚠ 이 앱에 다른 {@code @Scheduled} 빈을 추가한다면, 그것들도 이 스위치에 함께 묶인다는 뜻이다 —
 * 그때는 활성화를 이 조건에서 떼어 내야 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "user.cleanup.expired-data", name = "enabled", havingValue = "true")
public class CleanupSchedulingConfig {
}
