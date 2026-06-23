package com.skhynix.user.global.jwt;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT "검증"에 필요한 부품만 모아둔 공유 설정.
 *
 * <p>토큰 발급(로그인/회원가입)이 아니라 토큰 검증만 필요한 모듈(예: quiz)이
 * {@code @Import(JwtVerificationConfig.class)}로 이 설정만 가져다 쓸 수 있다.
 * 발급 로직(AuthService 등)은 따라오지 않는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtVerificationConfig {

    @Bean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        return new JwtTokenProvider(properties);
    }
}
