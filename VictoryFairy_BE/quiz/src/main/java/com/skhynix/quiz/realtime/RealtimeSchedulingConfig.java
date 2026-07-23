package com.skhynix.quiz.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SSE 하트비트({@link SseEmitterRegistry#heartbeat()})를 주기 실행하기 위해 스케줄링을 활성화한다.
 * quiz는 좁은 스캔({@code com.skhynix.quiz})이므로 이 설정이 스캔 범위 안에 있어 자동 등록된다.
 */
@Configuration
@EnableScheduling
public class RealtimeSchedulingConfig {
}
