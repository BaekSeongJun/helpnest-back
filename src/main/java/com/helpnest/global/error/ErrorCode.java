// @owner BSJ
package com.helpnest.global.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러 코드 enum 이 구현하는 인터페이스.
 * code 는 {@code {DOMAIN}_{설명}} 형식 (docs/04 §1.2 prefix 소유 규칙 준수).
 *
 * <pre>
 * public enum TicketErrorCode implements ErrorCode {
 *     INVALID_TRANSITION(HttpStatus.CONFLICT, "TICKET_INVALID_TRANSITION", "변경할 수 없는 상태입니다.");
 *     ...
 * }
 * </pre>
 */
public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();
}
