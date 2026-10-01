// @owner PMJ
package com.helpnest.domain.assignment.error;

import org.springframework.http.HttpStatus;

import com.helpnest.global.error.ErrorCode;

/**
 * 배정 도메인 에러 코드. prefix {@code ASSIGN_} 는 박민재 소유다(docs/04 §1.2).
 *
 * <p>{@link #NO_AVAILABLE_AGENT} 는 docs/04 §1.2 에 예시로 적힌 코드이므로 문자열을 바꾸지 않는다.
 * 자동 배정이 실패했을 때 쓰이며, 상담원이 모두 OFF 인 상태는 요청이 잘못된 것이 아니라
 * 서버 상태와 충돌하는 것이므로 409 CONFLICT 다.
 */
public enum AssignErrorCode implements ErrorCode {

    NO_AVAILABLE_AGENT(HttpStatus.CONFLICT, "ASSIGN_NO_AVAILABLE_AGENT", "배정할 수 있는 상담원이 없습니다."),
    NOT_AGENT(HttpStatus.BAD_REQUEST, "ASSIGN_NOT_AGENT", "상담원이 아닌 계정에는 배정할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AssignErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
