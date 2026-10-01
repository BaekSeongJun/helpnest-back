// @owner PMJ
package com.helpnest.domain.ticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 수동 배정·재배정 요청 (docs/04 §7 PATCH /api/console/tickets/{id}/assign, LEAD+).
 *
 * <p>agentId 가 실제로 상담원인지는 MemberQueryPort 로 확인하며, 아니면
 * {@code ASSIGN_NOT_AGENT} 다. 여기서는 값이 왔는지만 본다.
 */
public record TicketAssignRequest(
        @NotNull(message = "배정할 상담원을 선택해 주세요.")
        Long agentId,

        @Size(max = 500, message = "메모는 500자 이하로 입력해 주세요.")
        String memo) {
}
