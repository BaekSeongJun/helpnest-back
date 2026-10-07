// @owner PMJ
package com.helpnest.domain.chat.error;

import org.springframework.http.HttpStatus;

import com.helpnest.global.error.ErrorCode;

/**
 * 채팅 도메인 에러 코드. prefix {@code CHAT_} 는 박민재 소유다(docs/04 §1.2).
 * 상태 코드 기준은 {@code TicketErrorCode} 와 같다 — 지금 상태와 충돌하면 409.
 */
public enum ChatErrorCode implements ErrorCode {

    INVALID_STATE(HttpStatus.CONFLICT, "CHAT_INVALID_STATE", "현재 채팅 상태에서는 처리할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT_NOT_FOUND", "채팅방을 찾을 수 없습니다."),
    NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "CHAT_NOT_PARTICIPANT", "참여 중인 채팅방이 아닙니다."),
    WAIT_NOT_EXPIRED(HttpStatus.CONFLICT, "CHAT_WAIT_NOT_EXPIRED", "대기 5분이 지난 뒤에 문의로 남길 수 있습니다."),
    EMPTY_MESSAGES(HttpStatus.BAD_REQUEST, "CHAT_EMPTY_MESSAGES", "문의로 남길 메시지를 먼저 입력해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ChatErrorCode(HttpStatus status, String code, String message) {
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
