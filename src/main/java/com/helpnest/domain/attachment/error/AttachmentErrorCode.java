// @owner BSJ
package com.helpnest.domain.attachment.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AttachmentErrorCode implements ErrorCode {

    EMPTY(HttpStatus.BAD_REQUEST, "ATTACHMENT_EMPTY", "첨부할 파일을 선택해 주세요."),
    TOO_MANY(HttpStatus.BAD_REQUEST, "ATTACHMENT_TOO_MANY", "첨부는 최대 5개까지 가능합니다."),
    TOO_LARGE(HttpStatus.BAD_REQUEST, "ATTACHMENT_TOO_LARGE", "파일당 10MB 이하만 올릴 수 있습니다."),
    INVALID_TYPE(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID_TYPE", "허용되지 않는 파일 형식입니다."),
    LINK_DENIED(HttpStatus.BAD_REQUEST, "ATTACHMENT_LINK_DENIED", "연결할 수 없는 첨부가 포함되어 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AttachmentErrorCode(HttpStatus status, String code, String message) {
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
