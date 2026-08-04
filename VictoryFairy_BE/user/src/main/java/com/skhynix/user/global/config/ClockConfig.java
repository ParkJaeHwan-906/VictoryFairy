package com.skhynix.user.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "현재 시각"의 단일 출처. 운영 파드는 UTC 로 돈다(실측 확인, {@code TZ} 미설정) — {@code LocalDate.now()}
 * 처럼 시스템 기본 시간대에 의존하면 KST 자정~오전 9시 사이에 하루 전 날짜가 나온다. 그래서 시간대를
 * {@code Asia/Seoul} 로 코드에서 고정한 {@link Clock} 빈을 등록하고, 값 대신 이 빈을 주입받아 쓰게 해
 * 테스트에서 {@code Clock.fixed(...)} 로 날짜 경계를 검증할 수 있게 한다.
 */
@Configuration
public class ClockConfig {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
