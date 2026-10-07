// @owner PMJ
package com.helpnest.domain.ticket.dto;

import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 콘솔 티켓 목록 갱신 신호 ({@code /topic/console/tickets}, docs/04 §11).
 *
 * <p>목록 한 줄을 통째로 싣지 않는다 — 필터·정렬·권한(상담원은 자기 티켓만)은 목록 API 가 이미
 * 처리하므로, 화면은 이 신호를 받으면 현재 조건으로 목록을 다시 불러온다. 필드는 화면이 "지금 다시
 * 불러올 가치가 있는지" 거를 때 쓰는 최소한이다.
 *
 * @param event CREATED(신규 접수) | UPDATED(상태·담당자 변경)
 */
public record ConsoleTicketEvent(Long ticketId, String event, TicketStatus status, TicketPriority priority,
        Long agentId) {
}
