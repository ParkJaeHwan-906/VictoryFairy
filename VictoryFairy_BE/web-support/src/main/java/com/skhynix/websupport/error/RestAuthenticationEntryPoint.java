package com.skhynix.websupport.error;

import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증되지 않은 요청에 401을 내려주는 진입점. formLogin/httpBasic을 모두 disable한 이 프로젝트는 이 빈이
 * 없으면 Spring Security 기본값({@code Http403ForbiddenEntryPoint})으로 떨어져 "인증 안 됨"과 "권한 없음"이
 * 둘 다 403이 되어 클라이언트가 토큰 만료를 감지할 수 없다.
 *
 * <p>호출 시점이 {@code ExceptionTranslationFilter}(={@code DispatcherServlet} 바깥)라
 * {@link GlobalExceptionHandler}가 못 잡는 경로 — 그래서 표준 응답({@link ApiResponse})을 여기서 직접
 * 직렬화한다.
 */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.fail(ErrorCode.UNAUTHENTICATED.getMessage()));
    }
}
