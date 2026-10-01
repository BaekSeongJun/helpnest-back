// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.time.OffsetDateTime;

import com.helpnest.domain.ticket.entity.Sentiment;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 목록 한 행 (CS-01 티켓함 10열, CU-08 내 문의 목록).
 *
 * <h2>customerName·agentName 을 함께 담는 이유</h2>
 * 티켓은 member 를 연관관계로 들고 있지 않아(Ticket 클래스 주석) 이름이 티켓 안에 없다.
 * 행마다 프론트가 회원 API 를 또 부르면 N+1 호출이 되므로 서버가 MemberQueryPort 또는
 * 읽기 전용 JOIN 으로 채워서 내려준다.
 *
 * <h2>SLA 필드 4개를 모두 내려주는 이유</h2>
 * 프론트 공용 {@code SlaBadge} 가 {@code dueAt·respondedAt·breached·warning} 을 받아 표시만 한다.
 * 임박 판정 기준인 {@code warning_ratio} 는 sla_policy 에 있어 프론트가 계산할 수 없으므로
 * {@code slaWarned} 를 서버가 판단해 내려준다.
 *
 * @param customerId 회원이면 member_id, 비회원이면 null
 * @param sentiment  AI 분류 전이면 null (중립이 아니다)
 */
public record TicketListItemResponse(
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
        OffsetDateTime createdAt) {
}
