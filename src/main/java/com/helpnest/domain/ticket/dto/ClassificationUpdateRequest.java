// @owner PMJ
package com.helpnest.domain.ticket.dto;

import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketPriority;

import jakarta.validation.constraints.NotNull;

/**
 * 상담원의 분류 수동 수정 (docs/04 §7 PATCH /api/console/tickets/{id}/classification).
 *
 * <p>우선순위가 바뀌면 SLA 기한을 다시 계산해야 하고(PRD 6.1), 신수진의
 * {@code AiResultPort.markOverridden} 으로 "AI 결과를 사람이 고쳤다"는 기록을 남겨야 한다
 * (docs/02 §5.2). 둘 다 서비스 책임이다.
 */
public record ClassificationUpdateRequest(
        @NotNull(message = "유형을 선택해 주세요.")
        TicketCategory category,

        @NotNull(message = "우선순위를 선택해 주세요.")
        TicketPriority priority) {
}
