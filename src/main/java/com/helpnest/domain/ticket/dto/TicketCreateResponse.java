// @owner PMJ
package com.helpnest.domain.ticket.dto;

import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketStatus;

/** 접수 결과 (docs/04 §7). 비회원은 ticketNo 를 저장해 두어야 조회할 수 있다(CU-04) */
public record TicketCreateResponse(Long ticketId, String ticketNo, TicketStatus status) {

    public static TicketCreateResponse from(Ticket ticket) {
        return new TicketCreateResponse(ticket.getId(), ticket.getTicketNo(), ticket.getStatus());
    }
}
