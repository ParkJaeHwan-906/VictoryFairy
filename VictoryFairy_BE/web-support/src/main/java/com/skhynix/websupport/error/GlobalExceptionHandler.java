package com.skhynix.websupport.error;

import com.skhynix.common.error.BusinessDataException;
import com.skhynix.common.error.BusinessException;
import com.skhynix.common.error.ErrorCode;
import com.skhynix.common.response.ApiResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
