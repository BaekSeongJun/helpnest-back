// @owner BSJ
package com.helpnest.global.error;

import lombok.Getter;

/**
 * 서비스 계층에서 던지는 예외. GlobalExceptionHandler 가 ErrorCode 의 상태·코드로 응답한다.
 * 메시지에 값을 넣어야 하면 두 번째 생성자 사용 (예: "RECEIVED에서 RESOLVED로 변경할 수 없습니다.").
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
