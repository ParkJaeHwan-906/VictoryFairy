package com.skhynix.websupport.error;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode.getMessage()));
    }

    /**
     * {@link BusinessDataException}만 {@code data}를 실은 실패 응답으로 내보낸다.
     *
     * <p>{@code @ExceptionHandler}는 던져진 타입에 가장 가까운 핸들러를 고르므로, 이 메서드는
     * {@code BusinessDataException}으로 던진 예외에만 걸리고 {@code BusinessException}으로 던진
     * 기존 예외는 위 {@link #handleBusiness}에 그대로 간다 — 즉 <b>기존 응답의 {@code data:null}은
     * 유지된다.</b> 두 핸들러를 하나로 합치지 말 것(합치면 그 계약이 한 곳에서 통째로 흔들린다).
     */
    @ExceptionHandler(BusinessDataException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessData(BusinessDataException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode.getMessage(), e.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("입력값이 올바르지 않습니다.", errors));
    }

    /**
     * 이 핸들러가 없으면 스프링 기본 에러 본문이 그대로 나가
     * 공통 응답 규약({@code success/data/message})을 벗어난다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("필수 요청 파라미터가 누락되었습니다: " + e.getParameterName()));
    }

    /**
     * 업로드 크기 초과를 {@code ApiResponse} 래퍼가 붙은 413으로 내보낸다.
     *
     * <p>{@code MaxUploadSizeExceededException} 하나만 잡는 근거: 서블릿 컨테이너(톰캣)가
     * {@code max-file-size}/{@code max-request-size}/커넥터 {@code maxPostSize} 초과로 던진
     * {@code IllegalStateException}을 스프링이 멀티파트 해석 단계
     * ({@code StandardMultipartHttpServletRequest#handleParseFailure})에서 이 타입으로 바꿔 올리므로,
     * 컨테이너 단에서 시작된 경우까지 이 한 타입으로 모인다. 부모인 {@code MultipartException}을 잡으면
     * 크기와 무관한 멀티파트 파싱 실패(깨진 boundary 등)까지 413이 되므로 잡지 않는다.
     *
     * <p>이 예외는 {@code DispatcherServlet#checkMultipart} 단계, 즉 핸들러가 정해지기 전에 나오지만
     * dispatch try 블록 안이라 {@code @RestControllerAdvice}가 그대로 받는다
     * (컨트롤러 밖에서 터져 별도 진입점이 필요한 401과 다르다 — {@code RestAuthenticationEntryPoint}).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        ErrorCode errorCode = ErrorCode.PROFILE_IMAGE_TOO_LARGE;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode.getMessage()));
    }
    /**
     * 지원하지 않는 {@code Content-Type}을 {@code ApiResponse} 래퍼가 붙은 415로 내보낸다.
     *
     * <p>이 핸들러가 없으면 멀티파트 전용 엔드포인트에 JSON 본문을 보냈을 때 스프링 기본 에러 본문이
     * 그대로 나간다 — 같은 엔드포인트의 413({@link #handleMaxUploadSizeExceeded})은 래퍼가 붙는데
     * 415만 안 붙는 비대칭이었다(실측).
     *
     * <p>본문에 {@code e.getMessage()}를 싣지 않는다: 스프링이 만드는 문구는 지원 미디어타입 목록까지
     * 나열해 내부 배선을 드러낸다. 클라이언트가 고칠 것은 "형식이 틀렸다" 하나뿐이다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail("지원하지 않는 요청 형식(Content-Type)입니다."));
    }

    /**
     * 존재하는 경로에 잘못된 HTTP 메서드로 온 요청을 래퍼가 붙은 405로 내보낸다.
     *
     * <p>실제로 밟히는 경로라서 넣는다 — 다만 {@code permitAll}이 GET으로만 열린 경로(예: user의
     * {@code /teams}, {@code /players}, {@code /games})는 비-GET이면 시큐리티 단계에서 먼저 401이 되어
     * 여기까지 오지 않는다. 여기 오는 것은 <b>인증을 통과한</b> 요청이 메서드만 틀린 경우다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail("지원하지 않는 요청 메서드입니다."));
    }

    /**
     * 읽을 수 없는 요청 본문(깨진 JSON·빈 본문·타입이 맞지 않는 필드)을 래퍼가 붙은 400으로 내보낸다.
     *
     * <p>{@code e.getMessage()}는 절대 싣지 않는다 — Jackson이 만드는 문구에는 DTO 클래스명과 필드 경로가
     * 그대로 들어 있어 내부 구조가 노출된다. 필드 단위 안내가 필요한 검증 실패는 이 경로가 아니라
     * {@link #handleValidation}(본문 파싱에 성공한 뒤의 {@code @Valid} 위반)이 담당한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("요청 본문을 읽을 수 없습니다. 형식을 확인해 주세요."));
    }

    /**
     * 경로 변수·쿼리 파라미터의 타입이 맞지 않는 요청을 래퍼가 붙은 400으로 내보낸다
     * (예: {@code Long quizId} 자리에 문자열).
     *
     * <p>{@link #handleMissingParameter}(누락)와 같은 계열의 "요청 파라미터가 잘못됐다"이고, 파라미터
     * <b>이름</b>만 싣는 것도 같은 규칙이다. 들어온 값은 싣지 않는다 — 그대로 돌려주면 응답이 클라이언트가
     * 넣은 문자열의 반사 지점이 된다.
     *
     * <p>이 핸들러가 없으면 아래 {@link #handleUnexpected} catch-all이 이 예외를 집어 400이던 응답이
     * 500으로 바뀐다({@code MethodArgumentTypeMismatchException}은 {@code ErrorResponse}가 아니라
     * catch-all의 재던지기 조건에 걸리지 않는다). quiz의 {@code /quiz/{quizId}}·{@code /chat/{roomUid}}
     * 계열처럼 숫자 경로 변수를 쓰는 경로가 직접 영향을 받는다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail("요청 파라미터 형식이 올바르지 않습니다: " + e.getName()));
    }

    /**
     * 위 어느 핸들러에도 걸리지 않은 예외의 최종 방어선 — 응답은 래퍼가 붙은 500, 스택트레이스는 서버
     * 로그에만 남긴다. 이게 없으면 S3 실패({@code NoSuchBucketException}) 같은 예외가 스프링 기본 에러
     * 본문으로 나가고, {@code spring.web.error.include-stacktrace=always}인 dev에서는 스택트레이스까지
     * 응답에 실린다(실측).
     *
     * <p><b>더 구체적인 핸들러를 가로채지 않는다</b>: {@code @ExceptionHandler} 선택은
     * {@code ExceptionHandlerMethodResolver}가 {@code ExceptionDepthComparator}(상속 거리)로 하므로
     * {@code Exception}은 언제나 가장 먼 후보다. 즉 413·415·{@code BusinessException}·
     * {@code BusinessDataException} 등 기존 매핑은 그대로다.
     *
     * <p><b>다시 던지는 것들</b>(=여기서 500으로 바꾸면 안 되는 것들):
     * <ul>
     *   <li>{@link AccessDeniedException}/{@link AuthenticationException} — 스프링 시큐리티의 401/403은
     *       {@code ExceptionTranslationFilter}({@link RestAuthenticationEntryPoint} 포함)가 담당한다.
     *       지금은 메서드 시큐리티({@code @PreAuthorize})를 쓰는 곳이 없어 이 예외가 컨트롤러 단계까지
     *       올라오지 않지만, 나중에 도입되면 catch-all이 401/403을 통째로 500으로 바꿔 인증·인가 계약을
     *       깨뜨린다. 그 사고를 미리 막는 방어다.</li>
     *   <li>{@link ErrorResponse} 구현체 — 스프링이 이미 상태코드를 정해 둔 표준 MVC 예외다
     *       (404 {@code NoResourceFoundException}, 406 {@code HttpMediaTypeNotAcceptableException},
     *       503 {@code AsyncRequestTimeoutException} 등). 여기서 삼키면 4xx가 500으로 둔갑한다.
     *       래퍼를 씌우고 싶으면 catch-all이 아니라 위처럼 <b>타입별 핸들러를 추가</b>할 것.</li>
     *   <li>{@link AsyncRequestNotUsableException} — 클라이언트가 이미 끊어진 상태다(quiz의 SSE 구독에서
     *       일상적으로 발생). 스프링이 조용히 처리하도록 두지 않으면 끊길 때마다 ERROR 로그와 쓸 수 없는
     *       응답 쓰기 시도가 쌓인다.</li>
     * </ul>
     *
     * <p>다시 던진 예외는 {@code ExceptionHandlerExceptionResolver}가 "원래 예외와 같으면 경고 없이
     * {@code null} 반환"으로 흘려보내 다음 리졸버(→ 필터 체인)로 이어진다. 즉 <b>이 핸들러가 없던 때와
     * 똑같이</b> 처리된다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request)
            throws Exception {
        if (e instanceof AccessDeniedException
                || e instanceof AuthenticationException
                || e instanceof ErrorResponse
                || e instanceof AsyncRequestNotUsableException) {
            throw e;
        }
        // 응답에서 감추는 것이지 장애 분석을 포기하는 게 아니다 — 스택트레이스는 여기 한 곳에 반드시 남긴다.
        log.error("처리되지 않은 예외 [{} {}]", request.getMethod(), request.getRequestURI(), e);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode.getMessage()));
    }
}
