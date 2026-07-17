package com.skhynix.user.global.error;

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
 * 인증되지 않은 요청에 401을 내려주는 진입점.
 *
 * <p>이 빈을 명시하지 않으면 Spring Security는 {@code Http403ForbiddenEntryPoint}로 떨어진다.
 * 엔트리포인트를 등록하는 주체가 {@code formLogin}/{@code httpBasic}인데 이 프로젝트는 둘 다
 * disable하기 때문이다. 그 결과 "인증 안 됨"과 "권한 없음"이 모두 403이 되어, 클라이언트가
 * access 토큰 만료를 감지해 {@code /api/auth/refresh}를 호출하는 표준 흐름을 탈 수 없다.
 *
 * <p>호출 시점은 {@code ExceptionTranslationFilter} 안, 즉 {@code DispatcherServlet} 바깥이라
 * {@link GlobalExceptionHandler}가 잡지 못한다. 그래서 표준 응답 포맷을 여기서 직접 직렬화해
 * 필터 단계 에러도 컨트롤러 단계 에러와 같은 모양({@link ApiResponse})으로 나가도록 맞춘다.
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
