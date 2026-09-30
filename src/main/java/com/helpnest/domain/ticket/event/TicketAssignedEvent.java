// @owner PMJ
package com.helpnest.domain.ticket.event;

/**
 * 티켓에 담당 상담원이 배정됐을 때 발행한다(docs/02 §5.1, §5.3 시퀀스).
 *
 * <h2>구독자와 처리</h2>
 * 내(박민재) {@code NotificationListener} 가 받아 배정된 상담원에게 알림을 보낸다
 * (S2 범위, {@code /user/queue/notifications} STOMP 전송 + NOTIFICATION 적재).
 * 외부 도메인 구독자는 없다.
 *
 * <h2>구독자가 나뿐인데도 이벤트로 두는 이유</h2>
 * 배정은 자동 배정(분류 후 최소 부하)과 수동 재배정 두 경로에서 일어난다(PRD 6.2).
 * 알림을 각 경로에서 직접 호출하면 경로가 늘 때마다 빠뜨릴 수 있으므로, '배정됐다'는
 * 사실 하나만 발행하고 알림 책임은 리스너에 모은다.
 *
 * @param ticketId 배정된 티켓의 ticket_id
 * @param agentId  담당자로 지정된 상담원의 member_id
 */
public record TicketAssignedEvent(Long ticketId, Long agentId) {
}
