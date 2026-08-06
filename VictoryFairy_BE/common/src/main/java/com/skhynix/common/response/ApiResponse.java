package com.skhynix.common.response;

/**
 * 모든 모듈이 공유하는 표준 응답 포맷.
 *
 * <p>특정 프레임워크에 묶이지 않도록 순수 record로 둔다.
 */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }

    public static <T> ApiResponse<T> fail(String message, T data) {
        return new ApiResponse<>(false, data, message);
    }
}
