// @owner BSJ
package com.helpnest.domain.auth.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

    // 이메일 존재 여부를 노출하지 않도록 이메일·비밀번호 오류를 구분하지 않는다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_MEMBER(HttpStatus.FORBIDDEN, "AUTH_INACTIVE_MEMBER", "비활성화된 계정입니다. 관리자에게 문의해 주세요."),
    /** Refresh 없음·만료·폐기·재사용 → 프론트는 로그인 화면으로 */
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_INVALID", "로그인이 만료되었습니다. 다시 로그인해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus status, String code, String message) {
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
