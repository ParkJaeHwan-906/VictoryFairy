package com.skhynix.common.error;

/**
 * 비즈니스 규칙 위반 시 던지는 공통 예외. ErrorCode로 상태/메시지를 전달한다.
 * RuntimeException이므로 @Transactional 메서드에서 던지면 롤백된다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
