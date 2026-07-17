package com.skhynix.user.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.skhynix.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RestAuthenticationEntryPoint}가 401과 {@code ApiResponse} 포맷의 JSON 바디를 직접
 * 직렬화하는지 스프링 컨텍스트 없이 검증한다. 이 엔트리포인트는 {@code ExceptionTranslationFilter}
 * (서블릿 필터 단계, {@code DispatcherServlet} 바깥)에서 호출되므로 {@link GlobalExceptionHandler}가
 * 관여하지 않는 경로다 — 그래서 직접 직렬화 로직을 단위 테스트로 고정해 둔다.
 */
class RestAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);

    @Test
    @DisplayName("인증 예외가 발생하면 401 상태와 UTF-8 JSON content-type을 응답에 설정한다")
    void commence_setsUnauthorizedStatusAndUtf8JsonContentType() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        entryPoint.commence(request, response, new BadCredentialsException("bad credentials"));

        // then
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase(StandardCharsets.UTF_8.name());
    }

    @Test
    @DisplayName("응답 바디는 success:false, message는 ErrorCode.UNAUTHENTICATED의 메시지, data는 null이다")
    void commence_writesApiResponseFailBodyWithUnauthenticatedMessage() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // when
        entryPoint.commence(request, response, new BadCredentialsException("bad credentials"));

        // then
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("message").asString()).isEqualTo(ErrorCode.UNAUTHENTICATED.getMessage());
    }
}
