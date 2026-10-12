// @owner BSJ
package com.helpnest.domain.auth.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {

    // 이메일 존재 여부를 노출하지 않도록 이메일·비밀번호 오류를 구분하지 않는다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INACTIVE_MEMBER(HttpStatus.FORBIDDEN, "AUTH_INACTIVE_MEMBER", "비활성화된 계정입니다. 관리자에게 문의해 주세요."),
    /** 가입 후 인증 링크를 누르지 않음. 비밀번호가 맞을 때만 낸다 → 프론트는 재발송 안내 */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "AUTH_EMAIL_NOT_VERIFIED", "이메일 인증이 필요합니다. 메일의 인증 링크를 확인해 주세요."),
    /** Refresh 없음·만료·폐기·재사용 → 프론트는 로그인 화면으로 */
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_INVALID", "로그인이 만료되었습니다. 다시 로그인해 주세요."),
    /** 비회원 조회: 티켓 없음·이메일 불일치·비밀번호 오류·회원 티켓을 구분하지 않는다 (열거 방지) */
    GUEST_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_GUEST_INVALID", "티켓번호, 이메일 또는 조회 비밀번호가 올바르지 않습니다."),
    /** 재설정 링크: 없음·만료·이미 사용·유형 불일치를 구분하지 않는다 */
    RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "AUTH_RESET_TOKEN_INVALID", "링크가 만료되었거나 이미 사용되었습니다. 다시 요청해 주세요."),
    /** 비밀번호 변경 시 현재 비밀번호 불일치 */
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_MISMATCH", "현재 비밀번호가 올바르지 않습니다.");

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
