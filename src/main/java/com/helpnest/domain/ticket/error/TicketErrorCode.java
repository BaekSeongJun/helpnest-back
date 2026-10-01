// @owner PMJ
package com.helpnest.domain.ticket.error;

import org.springframework.http.HttpStatus;

import com.helpnest.global.error.ErrorCode;

/**
 * 티켓 도메인 에러 코드. prefix {@code TICKET_} 는 박민재 소유다(docs/04 §1.2).
 *
 * <p>{@link #INVALID_TRANSITION} 의 코드 문자열은 {@link ErrorCode} 의 Javadoc 예시와
 * docs/04 §1.1 실패 응답 예시에 그대로 적혀 있다. 다른 담당자가 그 예시를 보고 프론트 분기를
 * 작성하므로 문자열을 바꾸면 안 된다.
 *
 * <p>상태 코드 선택 근거: 전이·종료처럼 "지금 상태와 충돌"하는 경우는 409 CONFLICT,
 * 담당자가 아니어서 막히는 경우는 403 FORBIDDEN 이다(401 은 인증 실패라 Security 가 처리한다).
 */
public enum TicketErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "티켓을 찾을 수 없습니다."),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "TICKET_INVALID_TRANSITION", "변경할 수 없는 상태입니다."),
    NOT_ASSIGNEE(HttpStatus.FORBIDDEN, "TICKET_NOT_ASSIGNEE", "담당자만 처리할 수 있습니다."),
    INVALID_CLASSIFICATION(HttpStatus.BAD_REQUEST, "TICKET_INVALID_CLASSIFICATION", "유형 또는 우선순위 값이 올바르지 않습니다."),
    CONTENT_TOO_LONG(HttpStatus.BAD_REQUEST, "TICKET_CONTENT_TOO_LONG", "내용은 5,000자까지 입력할 수 있습니다."),
    ALREADY_CLOSED(HttpStatus.CONFLICT, "TICKET_ALREADY_CLOSED", "이미 종료된 문의입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    TicketErrorCode(HttpStatus status, String code, String message) {
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
