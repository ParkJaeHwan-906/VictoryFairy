package com.skhynix.user.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// "현재 시각"의 단일 출처. 파드의 TZ 는 k8s Deployment env 가 정해 앱 코드만 봐서는 알 수 없다 —
// LocalDate.now() 처럼 시스템 기본 시간대에 기대면 KST 자정~오전 9시 사이에 하루 전 날짜가 나온다.
@Configuration
public class ClockConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
