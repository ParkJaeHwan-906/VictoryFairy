package com.skhynix.websupport.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT "검증"에 필요한 부품만 모아둔 공유 설정 — 발급 로직(AuthService 등)은 따라오지 않는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtVerificationConfig {

    @Bean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties);
    }
}
