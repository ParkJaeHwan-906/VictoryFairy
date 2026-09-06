package com.skhynix.websupport.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * {@link GlobalExceptionHandler#handleMaxUploadSizeExceeded} — 업로드 크기 초과를
 * {@code ApiResponse} 래퍼가 붙은 413으로 내보낸다. 요구사항:
 * {@code docs/requirements/user/profile-image.md} USER-PI-39, 40, 107(해소 방침 3).
 *
 * <p>순수 POJO 메서드 호출이라 Spring 컨텍스트 없이 검증한다 — {@code @RestControllerAdvice} 배선
 * 자체(핸들러가 실제로 이 예외에 걸리는지)는 컨트롤러 슬라이스(예: 업로드 엔드포인트 테스트)가 더
 * 정확히 증명하지만, 여기서는 <b>이 핸들러가 만드는 응답의 모양</b>만 고정한다.
 *
 * <p>이 핸들러는 user(8080)뿐 아니라 quiz(8081)에도 공유된다는 것이 USER-PI-107의 계약이다 — quiz에
 * 아직 업로드 경로가 없어 quiz 쪽 컨트롤러 슬라이스로는 지금 증명할 게 없고, 여기(web-support)의
 * 단위 테스트가 그 공유 계약의 유일한 증거다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("[USER-PI-39] MaxUploadSizeExceededException은 413으로 응답한다"
            + "(이 저장소 최초의 413)")
    void handleMaxUploadSizeExceeded_returns413() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(5 * 1024 * 1024L);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(413));
    }

    @Test
    @DisplayName("[USER-PI-40] 응답 본문이 ApiResponse 래퍼(success=false, data=null, message 포함) 형태다"
            + " — 아무것도 하지 않으면 스프링 기본 에러 본문이 나가 이 계약이 자동으로 깨진다")
    void handleMaxUploadSizeExceeded_wrapsBodyInApiResponse() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(5 * 1024 * 1024L);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.PROFILE_IMAGE_TOO_LARGE.getMessage());
    }

    // ---------- 415 HttpMediaTypeNotSupportedException ----------

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException은 415와 ApiResponse 래퍼(data=null)로 응답한다")
    void handleMediaTypeNotSupported_returns415WithWrapper() {
        HttpMediaTypeNotSupportedException exception =
                new HttpMediaTypeNotSupportedException("multipart/form-data 가 아님");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMediaTypeNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isNotBlank();
    }

    // ---------- 405 HttpRequestMethodNotSupportedException ----------

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException은 405와 ApiResponse 래퍼(data=null)로 응답한다")
    void handleMethodNotSupported_returns405WithWrapper() {
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotSupported(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isNotBlank();
    }

    // ---------- 400 HttpMessageNotReadableException ----------

    @Test
    @DisplayName("HttpMessageNotReadableException은 400과 ApiResponse 래퍼(data=null)로 응답하고, "
            + "Jackson이 만든 원본 메시지(클래스명·필드 경로 노출)를 그대로 싣지 않는다")
    void handleNotReadable_returns400WithWrapper_andDoesNotLeakJacksonMessage() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Cannot deserialize value of type `com.skhynix.user.auth.dto.SignupRequest`"
                        + " from Object value", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleNotReadable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).doesNotContain("SignupRequest").doesNotContain("com.skhynix");
    }

    // ---------- 400 MethodArgumentTypeMismatchException ----------

    @SuppressWarnings("unused")
    private void dummyTarget(Long id) {
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException은 400과 ApiResponse 래퍼(data=null)로 응답하고, "
            + "파라미터 이름만 싣고 들어온 값 자체는 싣지 않는다")
    void handleTypeMismatch_returns400WithWrapper_andDoesNotEchoValue() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyTarget", Long.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        String injectedValue = "<script>alert(1)</script>";
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                injectedValue, Long.class, "quizId", parameter, new NumberFormatException(injectedValue));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).contains("quizId").doesNotContain(injectedValue);
    }

    // ---------- 500 catch-all(handleUnexpected) ----------

    @Test
    @DisplayName("처리되지 않은 예외는 500과 ApiResponse 래퍼(data=null)로 응답한다")
    void handleUnexpected_returns500WithWrapper() throws Exception {
        RuntimeException exception = new RuntimeException("NoSuchBucketException: victoryfairy-asset");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(exception, new MockHttpServletRequest("GET", "/api/anything"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    @Test
    @DisplayName("500 응답 본문은 고정 문구만 싣는다 — 예외 클래스명·원본 메시지·내부 경로·SQL을 담지 않는다")
    void handleUnexpected_doesNotLeakExceptionDetails() throws Exception {
        Exception exception = new java.sql.SQLException(
                "com.skhynix.user.profileimage.storage.S3ProfileImageStorage 에서 실패: "
                        + "SELECT * FROM users_account WHERE id = 42 at /internal/path");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(exception, new MockHttpServletRequest("POST", "/api/x"));

        String body = response.getBody().message();
        assertThat(body).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
        assertThat(body).doesNotContain("SQLException")
                .doesNotContain("SELECT")
                .doesNotContain("com.skhynix")
                .doesNotContain("/internal/path");
    }

    // ---------- catch-all이 재던지는 예외들(회귀 — 삼키면 401/403이 500이 되고 SSE가 ERROR 로그로 쌓인다) ----------

    @Test
    @DisplayName("AccessDeniedException은 삼켜지지 않고 원본 그대로 다시 던져진다")
    void handleUnexpected_rethrows_accessDeniedException() {
        AccessDeniedException exception = new AccessDeniedException("접근 거부");

        assertThatThrownBy(() -> handler.handleUnexpected(
                exception, new MockHttpServletRequest("GET", "/api/x")))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("AuthenticationException(구체 타입 BadCredentialsException)은 삼켜지지 않고 원본 그대로 "
            + "다시 던져진다")
    void handleUnexpected_rethrows_authenticationException() {
        BadCredentialsException exception = new BadCredentialsException("인증 실패");

        assertThatThrownBy(() -> handler.handleUnexpected(
                exception, new MockHttpServletRequest("GET", "/api/x")))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("ErrorResponse 구현체(NoResourceFoundException 등 스프링 표준 예외)는 삼켜지지 않고 원본 "
            + "그대로 다시 던져진다 — 여기서 삼키면 404 같은 기존 4xx가 500으로 둔갑한다")
    void handleUnexpected_rethrows_errorResponse() {
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "/no-route", "no route");

        assertThatThrownBy(() -> handler.handleUnexpected(
                exception, new MockHttpServletRequest("GET", "/no-route")))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("AsyncRequestNotUsableException(SSE 클라이언트 연결 끊김)은 삼켜지지 않고 원본 그대로 "
            + "다시 던져진다 — 삼키면 연결이 끊길 때마다 ERROR 로그가 쌓인다")
    void handleUnexpected_rethrows_asyncRequestNotUsableException() {
        AsyncRequestNotUsableException exception = new AsyncRequestNotUsableException("연결 끊김");

        assertThatThrownBy(() -> handler.handleUnexpected(
                exception, new MockHttpServletRequest("GET", "/quiz/subscribe")))
                .isSameAs(exception);
    }

    // ---------- catch-all은 구체 핸들러를 가로채지 않는다(실제 Spring 예외 해석 로직으로 고정) ----------

    /**
     * {@code @ExceptionHandler} 메서드 선택은 스프링이 {@code ExceptionDepthComparator}(상속 거리)로
     * 하는 일이라, 직접 handler 메서드를 호출하는 이 파일의 다른 테스트들은 "그 선택 로직 자체"는
     * 증명하지 못한다. {@link ExceptionHandlerMethodResolver}는 그 실제 선택 로직을 그대로 수행하는
     * 스프링 내부 클래스라 여기서 재사용하면 Spring 컨텍스트 없이도 "어떤 예외가 어떤 메서드로
     * 가는가"를 정확히 고정할 수 있다.
     */
    private static final ExceptionHandlerMethodResolver RESOLVER =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    @DisplayName("BusinessException은 catch-all이 아니라 handleBusiness로 간다(data:null 계약 불변)")
    void resolution_businessException_goesToHandleBusiness() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(BusinessException.class);

        assertThat(resolved.getName()).isEqualTo("handleBusiness");
    }

    @Test
    @DisplayName("BusinessDataException은 catch-all은 물론 handleBusiness도 아니라 handleBusinessData로 "
            + "간다 — 이 저장소의 기존 회귀 지점(닉네임 쿨다운 429+data)이 계속 지켜지는지 고정한다")
    void resolution_businessDataException_goesToHandleBusinessData() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(BusinessDataException.class);

        assertThat(resolved.getName()).isEqualTo("handleBusinessData");
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException(413)은 catch-all이 아니라 전용 핸들러로 간다")
    void resolution_maxUploadSizeExceeded_goesToOwnHandler() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(MaxUploadSizeExceededException.class);

        assertThat(resolved.getName()).isEqualTo("handleMaxUploadSizeExceeded");
    }

    @Test
    @DisplayName("MissingServletRequestParameterException(400)은 catch-all이 아니라 전용 핸들러로 간다")
    void resolution_missingParameter_goesToOwnHandler() {
        Method resolved =
                RESOLVER.resolveMethodByExceptionType(MissingServletRequestParameterException.class);

        assertThat(resolved.getName()).isEqualTo("handleMissingParameter");
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException(415)은 catch-all이 아니라 전용 핸들러로 간다")
    void resolution_mediaTypeNotSupported_goesToOwnHandler() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(HttpMediaTypeNotSupportedException.class);

        assertThat(resolved.getName()).isEqualTo("handleMediaTypeNotSupported");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException(405, ErrorResponse 구현체이기도 함)은 catch-all이 "
            + "아니라 전용 핸들러로 간다 — ErrorResponse 재던지기 규칙보다 구체 핸들러가 우선한다")
    void resolution_methodNotSupported_goesToOwnHandler() {
        Method resolved =
                RESOLVER.resolveMethodByExceptionType(HttpRequestMethodNotSupportedException.class);

        assertThat(resolved.getName()).isEqualTo("handleMethodNotSupported");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException(400)은 catch-all이 아니라 전용 핸들러로 간다")
    void resolution_notReadable_goesToOwnHandler() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(HttpMessageNotReadableException.class);

        assertThat(resolved.getName()).isEqualTo("handleNotReadable");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException(400)은 catch-all이 아니라 전용 핸들러로 간다")
    void resolution_typeMismatch_goesToOwnHandler() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(MethodArgumentTypeMismatchException.class);

        assertThat(resolved.getName()).isEqualTo("handleTypeMismatch");
    }

    @Test
    @DisplayName("매핑되지 않은 일반 예외(RuntimeException)는 catch-all(handleUnexpected)로 떨어진다"
            + " — 최종 방어선이 여전히 살아 있다")
    void resolution_unmappedException_fallsThroughToUnexpected() {
        Method resolved = RESOLVER.resolveMethodByExceptionType(RuntimeException.class);

        assertThat(resolved.getName()).isEqualTo("handleUnexpected");
    }
}
