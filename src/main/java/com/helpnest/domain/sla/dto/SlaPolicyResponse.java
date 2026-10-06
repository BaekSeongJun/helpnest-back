// @owner PMJ
package com.helpnest.domain.sla.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.ticket.entity.TicketPriority;

/**
 * SLA 정책 한 행 (docs/04 §8).
 *
 * <p>{@code warningMinutes} 는 저장된 값이 아니라 {@code responseMinutes × warningRatio} 다.
 * 관리 화면이 "0.80" 만 보고 48분을 암산하게 두면 반올림 방식까지 프론트가 따라 구현해야 하고,
 * 그 순간 화면과 서버의 임박 기준이 갈린다({@link SlaPolicy#warningMinutes()} 주석).
 */
public record SlaPolicyResponse(
        TicketPriority priority,
        int responseMinutes,
        BigDecimal warningRatio,
        long warningMinutes,
        OffsetDateTime updatedAt) {

    public static SlaPolicyResponse from(SlaPolicy policy) {
        return new SlaPolicyResponse(policy.getPriority(), policy.getResponseMinutes(),
                policy.getWarningRatio(), policy.warningMinutes(), policy.getUpdatedAt());
    }
}
