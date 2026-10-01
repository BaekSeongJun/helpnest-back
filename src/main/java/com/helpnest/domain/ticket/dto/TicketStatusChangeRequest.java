// @owner PMJ
package com.helpnest.domain.ticket.dto;

import com.helpnest.domain.ticket.entity.TicketStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 상태 변경 요청 (docs/04 §7 PATCH /api/console/tickets/{id}/status).
 *
 * <p>전이 가능 여부는 여기서 검증하지 않는다. {@code TicketStateMachine} 이 판정하고 서비스가
 * {@code TICKET_INVALID_TRANSITION} 으로 바꿔 던진다 — 애너테이션으로 표현하면 전이표가
 * 두 곳에 생긴다.
 */
public record TicketStatusChangeRequest(
        @NotNull(message = "변경할 상태를 선택해 주세요.")
        TicketStatus toStatus,

        @Size(max = 500, message = "메모는 500자 이하로 입력해 주세요.")
        String memo) {
}
