package com.skhynix.user.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * user 모듈의 모든 컨트롤러에 외부 경로 접두사 {@code /api/member}를 전역으로 붙인다.
 *
 * <p>덕분에 컨트롤러는 자기 자원 이름만 갖는다 — {@code @RequestMapping("/auth")}는
 * 실제로 {@code /api/member/auth/**}로 노출된다. 컨트롤러가 늘어나도 접두사를 반복하지 않는다.
 *
 * <p>{@code server.servlet.context-path}를 쓰지 않은 이유가 둘 있다.
 * <ul>
 *   <li>context-path는 헬스체크({@code /health})까지 접두사 아래로 밀어버려 ALB Ingress의
 *       {@code healthcheck-path}가 깨진다. 이 방식은 MVC 핸들러 매핑에만 영향을 줘 그대로 둔다.</li>
 *   <li>Spring Security 필터는 MVC 매핑보다 먼저 돌아 원본 URI를 본다. 그래서 SecurityConfig의
 *       {@code requestMatchers}는 외부 경로({@code /api/member/auth/**})를 그대로 쓰면 된다.
 *       context-path였다면 잘린 경로를 써야 해 혼동이 생긴다.</li>
 * </ul>
 *
 * <p>⚠ ALB는 forward 시 경로 rewrite를 지원하지 않는다(redirect만 가능). 따라서 여기의 접두사는
 * {@code k8s/22-ingress.yaml}의 path와 문자 그대로 일치해야 한다.
 */
@Configuration
public class ApiPathPrefixConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/member", HandlerTypePredicate.forBasePackage("com.skhynix.user"));
    }
}
