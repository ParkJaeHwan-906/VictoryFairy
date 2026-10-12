package com.skhynix.chat.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chat.presence.*} 설정(CHAT-PR-29). 기본값은 application.yaml 이 단일 출처다. 환경변수(relaxed binding)로 덮어쓴다.
 *
 * <p>{@link ChatProperties} 에 넣지 않고 따로 둔 이유는 {@link ChatLikesProperties} 와 같다 — 그 레코드의 정식 생성자를
 * 테스트 여러 곳이 직접 부르고 있어 성분을 하나 늘리면 그 호출이 전부 깨진다.
 *
 * <p>생존 갱신 주기는 키가 없다. 하트비트 15초(CHAT-GC-32)를 그대로 쓴다.
 */
@ConfigurationProperties("chat.presence")
public record ChatPresenceProperties(
        long expireSeconds,
        long broadcastIntervalMs,
        long keyTtlSeconds) {
}
