package com.skhynix.chat.subscription.controller;

import com.skhynix.chat.shared.ChatRoles;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.ObjectMapper;

/**
 * 구독 엔드포인트의 {@link BusinessException}(404·503)을 공유 {@code GlobalExceptionHandler} 보다 먼저 잡아
 * 응답에 직접 쓴다. 본문은 공유 핸들러와 같은 {@code ApiResponse.fail(message)} 이다.
 *
 * <p>⚠ SSE 클라이언트(fetch 폴리필)는 {@code Accept: text/event-stream} 만 보낸다. 공유 핸들러처럼
 * {@code ResponseEntity<ApiResponse>} 를 돌려주면 메시지 컨버터가 그 Accept 로 JSON 을 쓸 수 없어
 * (HttpMediaTypeNotAcceptable) 404·503 대신 500 이 나간다. 그래서 컨버터 협상을 거치지 않고 직접 쓴다.
 * web-support 를 고치지 않고 이 모듈 안에서 막으려고 컨트롤러 단위로 범위를 좁혔다.
 */
@RestControllerAdvice(assignableTypes = ChatSubscriptionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanProperty(name = ChatRoles.API, matchIfMissing = true)
@RequiredArgsConstructor
public class SubscriptionExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(BusinessException.class)
    public void handleBusiness(BusinessException e, HttpServletResponse response) throws IOException {
        ErrorCode errorCode = e.getErrorCode();
        response.setStatus(errorCode.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(errorCode.getMessage()));
    }
}
