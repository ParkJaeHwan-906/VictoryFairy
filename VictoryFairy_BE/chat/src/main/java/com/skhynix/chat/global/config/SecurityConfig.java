package com.skhynix.chat.global.config;

import com.skhynix.domain.user.repository.UserAccountRepository;
import com.skhynix.websupport.error.GlobalExceptionHandler;
import com.skhynix.websupport.error.RestAuthenticationEntryPoint;
import com.skhynix.websupport.jwt.JwtAuthenticationFilter;
import com.skhynix.websupport.jwt.JwtTokenProvider;
import com.skhynix.websupport.jwt.JwtVerificationConfig;
import jakarta.servlet.DispatcherType;
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
// web-support 가 컴포넌트 스캔(com.skhynix.chat) 밖이라 명시적으로 끌어온다. GlobalExceptionHandler 를 빼면
// AsyncRequestNotUsableException 재던지기도 빠져 SSE 가 끊길 때마다 ERROR 로그가 남는다.
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
                        // ASYNC: SSE 를 서버가 complete()(퇴장·축출·타임아웃)하면 같은 요청이 ASYNC 로 재디스패치되는데,
                        // 무상태(JWT)라 그 디스패치에는 SecurityContext 가 없어 인가가 거부되고 ERROR 로그 2줄이 남는다.
                        // 원 요청(REQUEST 디스패치)이 이미 인증·인가를 통과한 뒤라 재평가할 이유가 없다.
                        // ERROR: 컨테이너의 에러 포워드. 원 요청이 인가를 통과했든 거부됐든 그 결과를 그리는 단계이고,
                        // /error 는 이미 permitAll 이며 server.error.include-* 기본값(never)이라 메시지·스택을 싣지 않는다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/error").permitAll()
                        // context-path(/chat)는 필터 이전에 떨어지므로 접두사 없이 쓴다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                // formLogin/httpBasic 을 끄면 엔트리포인트 기본값이 403 이라 401 을 내리려면 명시해야 한다.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider, userAccountRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
