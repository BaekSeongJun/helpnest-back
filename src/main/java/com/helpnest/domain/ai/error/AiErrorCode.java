// @owner SSJ
package com.helpnest.domain.ai.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** AI 도메인 에러 코드. prefix {@code AI_} 는 신수진 소유(docs/04 §1.2) */
public enum AiErrorCode implements ErrorCode {

    NOT_ASSIGNEE(HttpStatus.FORBIDDEN, "AI_NOT_ASSIGNEE", "담당 상담원만 초안을 생성할 수 있습니다."),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE",
            "AI 초안을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AiErrorCode(HttpStatus status, String code, String message) {
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
