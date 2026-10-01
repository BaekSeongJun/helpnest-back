// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.helpnest.domain.ticket.entity.Sentiment;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 티켓 상세 (CU-07 문의 상세, CS-02 티켓 상세).
 *
 * <h2>목록 필드를 중첩하지 않고 펼쳐 담는 이유</h2>
 * {@code TicketListItemResponse} 를 필드로 품으면 JSON 이 한 단계 중첩돼 프론트가 목록과 상세에서
 * 서로 다른 경로로 같은 값을 읽어야 한다. 프론트 타입도 {@code TicketResponse extends TicketListItem}
 * 로 평탄하게 확정돼 있어 같은 모양을 유지한다.
 *
 * <h2>replies 에 내부 메모를 넣지 않는 경우</h2>
 * 고객용 조회(GET /api/tickets/{id})는 {@code is_internal = true} 를 <b>조회 쿼리에서</b> 걸러야
 * 한다. 이 record 에 담은 뒤 화면에서 숨기는 방식은 응답 본문에 이미 유출된 상태다
 * (TicketReply 의 "고객 노출 여부" 주석).
 */
public record TicketDetailResponse(
        Long ticketId,
        String ticketNo,
        String title,
        Long customerId,
        String customerName,
        TicketCategory category,
        TicketPriority priority,
        Sentiment sentiment,
        TicketStatus status,
        Long agentId,
        String agentName,
        OffsetDateTime firstResponseDueAt,
        OffsetDateTime firstRespondedAt,
        boolean slaWarned,
        boolean slaBreached,
        OffsetDateTime createdAt,
        String content,
        TicketChannel channel,
        List<TicketAttachmentResponse> attachments,
        List<ReplyResponse> replies,
        OffsetDateTime assignedAt,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        OffsetDateTime updatedAt) {
}
