package com.skhynix.common.error;

/**
 * 공통 에러 코드. status는 HTTP 상태값(int)으로 보관해 common이 spring 의존성을 갖지 않도록 한다.
 */
public enum ErrorCode {

    // 409 Conflict
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다."),
    DUPLICATE_TEL(409, "이미 사용 중인 전화번호입니다."),
    DUPLICATE_NICKNAME(409, "이미 사용 중인 닉네임입니다."),

    // 401 Unauthorized
    UNAUTHENTICATED(401, "인증이 필요합니다."),
    INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(401, "유효하지 않은 리프레시 토큰입니다."),
    EXPIRED_REFRESH_TOKEN(401, "만료되었거나 이미 무효화된 리프레시 토큰입니다."),

    // 400 Bad Request - 이메일 인증
    INVALID_VERIFICATION_CODE(400, "인증번호가 일치하지 않습니다."),
    EXPIRED_VERIFICATION_CODE(400, "만료되었거나 유효하지 않은 인증번호입니다."),
    VERIFICATION_ATTEMPTS_EXCEEDED(400, "인증 시도 횟수를 초과했습니다. 인증번호를 다시 발송해 주세요."),
    EMAIL_NOT_VERIFIED(400, "이메일 인증이 완료되지 않았습니다."),

    // 429 Too Many Requests - 이메일 인증
    EMAIL_SEND_COOLDOWN(429, "인증번호를 방금 발송했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
