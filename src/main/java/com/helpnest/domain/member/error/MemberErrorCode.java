// @owner BSJ
package com.helpnest.domain.member.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MemberErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "MEMBER_EMAIL_DUPLICATED", "이미 가입된 이메일입니다."),
    SELF_CHANGE_FORBIDDEN(HttpStatus.BAD_REQUEST, "MEMBER_SELF_CHANGE_FORBIDDEN", "본인 계정의 역할 변경·비활성화는 할 수 없습니다."),
    ROLE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "MEMBER_ROLE_NOT_ALLOWED", "허용되지 않는 역할 변경입니다."),
    NOT_AGENT(HttpStatus.FORBIDDEN, "MEMBER_NOT_AGENT", "상담원만 상담 가능 여부를 변경할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MemberErrorCode(HttpStatus status, String code, String message) {
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
