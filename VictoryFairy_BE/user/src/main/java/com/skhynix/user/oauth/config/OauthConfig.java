package com.skhynix.user.oauth.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 소셜 로그인 provider 호출용 HTTP 설정.
 *
 * <p><b>타임아웃이 이 설정의 존재 이유다.</b> 기본 {@code HttpClient} 는 읽기 타임아웃이 없어
 * provider 가 응답을 끊지 않으면 요청 스레드가 무한정 붙잡힌다 — 외부 장애 하나가 이 앱의 스레드
 * 풀을 통째로 말리는 형태로 번지며, 그때 사용자가 보는 것은 502 가 아니라 전면 무응답이다.
 *
 * <p>이 빈은 소셜 로그인 전용이다. 다른 외부 호출이 생기면 그쪽 타임아웃 요구가 다를 수 있으니
 * 이 빈을 공유하지 말고 각자 만들 것.
 */
@Configuration
@EnableConfigurationProperties(OauthProperties.class)
public class OauthConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient oauthRestClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
