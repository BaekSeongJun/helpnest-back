// @owner BSJ
package com.helpnest.global.common;

import com.helpnest.global.error.ErrorCode;

/**
 * 모든 API 공통 응답 형식 (docs/04 §1.1).
 * 컨트롤러는 {@code ApiResponse.ok(data)} 만 반환하고, 실패 응답은 GlobalExceptionHandler 가 만든다.
 */
public record ApiResponse<T>(boolean success, T data, ErrorBody error) {

    public record ErrorBody(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(errorCode.code(), message));
    }
}
