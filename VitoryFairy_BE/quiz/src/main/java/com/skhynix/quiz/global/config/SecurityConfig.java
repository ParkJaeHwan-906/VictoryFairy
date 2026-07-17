package com.skhynix.quiz.global.config;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.user.global.jwt.JwtAuthenticationFilter;
import com.skhynix.user.global.jwt.JwtTokenProvider;
import com.skhynix.user.global.jwt.JwtVerificationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@Import(JwtVerificationConfig.class) // user의 토큰 검증 부품만 가져옴 (발급 로직은 제외)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider tokenProvider,
            UserAccountRepository userAccountRepository) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, userAccountRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
