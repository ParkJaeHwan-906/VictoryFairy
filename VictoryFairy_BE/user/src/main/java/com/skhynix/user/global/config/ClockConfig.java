package com.skhynix.user.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "현재 시각"의 단일 출처. 시간대를 {@code Asia/Seoul} 로 <b>코드에서 고정</b>한 {@link Clock} 을 등록한다.
 *
 * <p><b>왜 시스템 기본 시간대를 쓰지 않는가</b>: 운영 파드는 UTC 로 돈다(실측 {@code kubectl exec user-app -- date}
 * → {@code UTC}, {@code TZ} 환경변수 없음). 이 상태에서 {@code LocalDate.now()} 처럼 시스템 기본 시간대에
 * 의존하면 KST 자정~오전 9시 사이에 <b>하루 전 날짜</b>가 나온다. KBO 경기 일정은 한국 날짜 기준이므로
 * 그 시간대에 접속한 사용자에게 전날 경기 목록이 보이는 오답이 된다.
 *
 * <p><b>왜 배포 설정({@code TZ=Asia/Seoul}) 에 맡기지 않는가</b>: ConfigMap/Dockerfile 의 환경변수는 코드 밖에서
 * 바뀔 수 있고, 로컬·CI·운영이 서로 다른 시간대로 돌면 같은 코드가 환경마다 다른 답을 낸다.
 * 도메인 규칙(한국 날짜)은 배포 설정이 아니라 코드가 스스로 보장해야 한다.
 *
 * <p><b>왜 {@code LocalDate.now(ZoneId)} 를 직접 부르지 않고 빈으로 주입하는가</b>: 그렇게 하면 "오늘"이 실제
 * 시계에 묶여 테스트에서 날짜 경계(자정 직후 등)를 검증할 수 없다. {@code Clock} 을 주입받으면 테스트가
 * {@code Clock.fixed(...)} 로 오늘을 고정할 수 있다.
 */
@Configuration
public class ClockConfig {

    /** KBO 일정 기준 시간대. 배포 환경의 {@code TZ} 와 무관하게 코드에서 고정한다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
