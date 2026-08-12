package com.skhynix.user.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "현재 시각"의 단일 출처. 파드의 {@code TZ} 는 k8s Deployment env(dev_infra 소관)가 정하므로 이
 * 앱 코드만 봐서는 알 수 없다 — {@code LocalDate.now()} 처럼 시스템 기본 시간대에 기대면 그 설정값
 * 하나에 날짜 계산이 통째로 좌우된다(빠지면 KST 자정~오전 9시 사이에 하루 전 날짜가 나온다). 그래서
 * 시간대를 {@code Asia/Seoul} 로 코드에서 고정한 {@link Clock} 빈을 등록하고, 값 대신 이 빈을
 * 주입받아 쓰게 해 파드 설정과 무관하게 날짜 경계를 보장하고, 테스트에서는 {@code Clock.fixed(...)}
 * 로 그 경계를 검증할 수 있게 한다.
 */
@Configuration
public class ClockConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
