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
                        // ALB 타깃 헬스체크. 종전 "/health" 는 처리할 핸들러가 없어 항상 404 였다
                        // (그래서 타깃이 Unhealthy → 503). actuator 경로로 교체한다.
                        // context-path(/api/member)는 필터 이전에 떨어지므로 접두사 없이 쓴다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        // context-path(/api/member)는 컨테이너가 필터 체인 이전에 떼므로
                        // 여기서는 접두사를 뺀 경로를 쓴다. 외부 노출 경로는 /api/member/auth/**.
                        .requestMatchers("/auth/**").permitAll()
                        // 구단 목록은 회원가입 등 로그인 이전 화면에서 필요해 인증 없이 연다.
                        // GET 으로 좁힌 이유: 읽기 전용 의도를 보안 설정에 드러내고, 이후 같은 경로에
                        // 쓰기 엔드포인트가 인증 없이 열린 채 추가되는 사고를 구조적으로 막는다
                        // (비-GET 은 405 가 아니라 401 로 떨어진다 — 의도된 결과).
                        // 위와 같은 이유로 context-path(/api/member) 접두사는 붙이지 않는다.
                        .requestMatchers(HttpMethod.GET, "/teams").permitAll()
                        // 선수 목록도 구단 목록과 같은 성격의 참조 데이터라 같은 이유·같은 방식(GET 한정)으로 연다.
                        .requestMatchers(HttpMethod.GET, "/players").permitAll()
                        // 경기 목록도 같은 성격의 공개 참조 데이터라 같은 이유·같은 방식(GET 한정)으로 연다.
                        .requestMatchers(HttpMethod.GET, "/games").permitAll()
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
