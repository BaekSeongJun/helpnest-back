// @owner PMJ
package com.helpnest.domain.ticket.event;

/**
 * 티켓 분류(유형·우선순위·감정)가 반영됐을 때 발행한다 — AI 결과와 상담원 수동 수정 모두.
 *
 * <p>구독자는 {@code ConsoleTicketBroadcaster} 하나다. 분류만 바뀌고 상태·담당자가 그대로면 다른
 * 이벤트가 없어 콘솔 목록이 옛 우선순위·불만 배지로 남았다(#116).
 *
 * @param ticketId 분류가 반영된 티켓의 ticket_id
 */
public record TicketClassifiedEvent(Long ticketId) {
}
