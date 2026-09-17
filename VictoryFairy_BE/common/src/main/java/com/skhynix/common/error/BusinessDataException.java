package com.skhynix.common.error;

/**
 * 실패 응답에 <b>도메인 데이터를 함께</b> 실어야 하는 비즈니스 예외.
 *
 * <p>{@link BusinessException}은 {@link ErrorCode}(status·message)만 들고 있어 그 응답의 {@code data}는
 * 예외 없이 {@code null}이다. "왜 막혔는지"에 더해 "언제 풀리는지" 같은 값을 함께 줘야 하는 거절이
 * 생겨 이 하위 타입을 열었다.
 *
 * <p><b>기존 계약을 건드리지 않으려고 상속으로 분리했다.</b> {@code BusinessException}에 data 필드를
 * 붙이고 공통 핸들러가 그것을 읽게 바꾸면 모든 비즈니스 예외 응답이 같은 코드 경로를 공유하게 되지만,
 * 이 타입으로 던진 예외만 전용 핸들러에 걸리므로({@code @ExceptionHandler}는 가장 구체적인 타입을
 * 고른다) {@code BusinessException}으로 던지는 기존 지점들의 응답은 문자 하나 바뀌지 않는다.
 *
 * <p>{@code data}가 {@code Object}인 이유: common은 특정 프레임워크·모듈에 묶이지 않아야 해서 응답
 * 페이로드 타입을 여기서 알 수 없다. 직렬화 가능한 값(주로 앱 모듈의 record)을 그대로 받는다.
 */
public class BusinessDataException extends BusinessException {

    private final Object data;

    public BusinessDataException(ErrorCode errorCode, Object data) {
        super(errorCode);
        this.data = data;
    }

    public Object getData() {
        return data;
    }
}
