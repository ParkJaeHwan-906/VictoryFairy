package com.skhynix.user.global.config;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.websupport.error.RestAuthenticationEntryPoint;
import com.skhynix.websupport.jwt.JwtAuthenticationFilter;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
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
                        // ALB 헬스체크. 종전 "/health"는 핸들러가 없어 항상 404(Unhealthy)였다 — actuator로 교체.
                        // context-path(/api/member)는 필터 이전에 떨어지므로 이 아래 경로들도 접두사를 붙이지 않는다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        // GET 으로 좁혀 열어 이후 같은 경로에 쓰기 엔드포인트가 인증 없이 추가되는 사고를 막는다
                        // (비-GET 은 405 가 아니라 401). 새 공개 조회 경로를 추가할 때 이 줄을 빠뜨리면
                        // anyRequest().authenticated() 에 걸려 401이 난다.
                        .requestMatchers(HttpMethod.GET, "/teams").permitAll()
                        .requestMatchers(HttpMethod.GET, "/players").permitAll() // teams와 같은 이유
                        .requestMatchers(HttpMethod.GET, "/games").permitAll()   // teams와 같은 이유
                        // 위 /games 매처는 정확 매칭이라 하위 경로를 커버하지 않는다 — 이 줄이 없으면 401.
                        .requestMatchers(HttpMethod.GET, "/games/lineup").permitAll()
                        .anyRequest().authenticated()
                )
                // formLogin/httpBasic을 모두 disable하면 엔트리포인트를 등록하는 주체가 없어
                // 기본값(Http403ForbiddenEntryPoint)으로 떨어진다. 401을 내리려면 명시가 필요하다.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, userAccountRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
