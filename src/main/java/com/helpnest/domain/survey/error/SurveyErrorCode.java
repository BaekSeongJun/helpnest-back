// @owner BSJ
package com.helpnest.domain.survey.error;

import com.helpnest.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SurveyErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "SURVEY_NOT_FOUND", "설문 링크가 올바르지 않습니다."),
    /** 72시간 경과 또는 재문의로 만료. 재해결되면 새 링크가 메일로 나간다 */
    EXPIRED(HttpStatus.GONE, "SURVEY_EXPIRED", "설문 기간이 지났습니다."),
    ALREADY_SUBMITTED(HttpStatus.CONFLICT, "SURVEY_ALREADY_SUBMITTED", "이미 응답해 주셨습니다. 감사합니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    SurveyErrorCode(HttpStatus status, String code, String message) {
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
