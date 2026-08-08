package com.skhynix.quiz.global.config;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.error.RestAuthenticationEntryPoint;
import com.skhynix.websupport.jwt.JwtAuthenticationFilter;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import com.skhynix.websupport.jwt.JwtVerificationConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
// web-support가 컴포넌트 스캔(com.skhynix.quiz) 밖이라 자동 감지되지 않으므로 명시적으로 끌어온다.
@Import({JwtVerificationConfig.class, GlobalExceptionHandler.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider tokenProvider,
            UserAccountRepository userAccountRepository, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error").permitAll()
                        // ALB 타깃 헬스체크. context-path(/rt)는 필터 이전에 떨어지므로 접두사 없이 쓴다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                // formLogin/httpBasic을 disable하면 엔트리포인트 기본값(403)으로 떨어져 401을 내리려면 명시가 필요하다.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, userAccountRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
